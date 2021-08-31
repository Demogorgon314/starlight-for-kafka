/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop;
import com.google.common.collect.ImmutableMap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.streamnative.pulsar.handlers.kop.utils.ConfigurationUtils;
import io.streamnative.pulsar.handlers.kop.utils.KopTopic;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.proxy.server.ProxyConfiguration;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.impl.AuthenticationUtil;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.naming.NamespaceName;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;

/**
 * Kafka Protocol Handler load and run by Pulsar Service.
 */
@Slf4j
public class KafkaProtocolProxyMain {

    public static final String PROTOCOL_NAME = "kafka";
    public static final String TLS_HANDLER = "tls";

    @Getter
    private KafkaServiceConfiguration kafkaConfig;

    private PulsarAdmin pulsarAdmin;
    private AuthenticationService authenticationService;

    public void initialize(ProxyConfiguration conf) throws Exception {

        authenticationService = new AuthenticationService(PulsarConfigurationLoader.convertFrom(conf));

        String auth = conf.getBrokerClientAuthenticationPlugin();
        String authParams = conf.getBrokerClientAuthenticationParameters();

       Authentication authentication = AuthenticationUtil.create(auth, authParams);

        pulsarAdmin = PulsarAdmin
                .builder()
                .authentication(authentication)
                .serviceHttpUrl((String) conf.getProperties().getOrDefault("webServiceUrl", "http://localhost:8080"))
                .allowTlsInsecureConnection(true) // TODO make this configurable
                .enableTlsHostnameVerification(false) // TODO make this configurable
                .build();

        // init config

        // when loaded with PulsarService as NAR, `conf` will be type of ServiceConfiguration
        kafkaConfig = ConfigurationUtils.create(conf.getProperties(), KafkaServiceConfiguration.class);

        // some of the configs value in conf.properties may not updated.
        // So need to get latest value from conf itself
        kafkaConfig.setAdvertisedAddress(conf.getAdvertisedAddress());
        kafkaConfig.setBindAddress(conf.getBindAddress());

        KopTopic.initialize(kafkaConfig.getKafkaTenant() + "/" + kafkaConfig.getKafkaNamespace());

        // Validate the namespaces
        for (String fullNamespace : kafkaConfig.getKopAllowedNamespaces()) {
            final String[] tokens = fullNamespace.split("/");
            if (tokens.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid namespace '" + fullNamespace + "' in kopAllowedNamespaces config");
            }
            NamespaceName.validateNamespaceName(tokens[0], tokens[1]);
        }


        log.info("AuthenticationEnabled:  {}", kafkaConfig.isAuthenticationEnabled());
        log.info("SaslAllowedMechanisms:  {}", kafkaConfig.getSaslAllowedMechanisms());
    }

    public static void main(String ... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                log.info("uncaughtException in thread {}",t, e);
            }
        });
        String configFile = args.length > 0 ? args[0] : "conf/kop_proxy.conf";
        KafkaProtocolProxyMain proxy = new KafkaProtocolProxyMain();
        ProxyConfiguration serviceConfiguration = PulsarConfigurationLoader.create(configFile, ProxyConfiguration.class);
        proxy.initialize(serviceConfiguration);
        proxy.start();
        log.info("Started");
        Thread.sleep(Integer.MAX_VALUE);
        proxy.close();
    }

    public void start() {

        log.info("Starting KafkaProtocolProxy, kop version is: '{}'", KopVersion.getVersion());
        log.info("Git Revision {}", KopVersion.getGitSha());
        log.info("Built by {} on {} at {}",
            KopVersion.getBuildUser(),
            KopVersion.getBuildHost(),
            KopVersion.getBuildTime());
        newChannelInitializers().forEach( (address, initializer) ->{
            System.out.println("Starting protocol at "+address);
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(new NioEventLoopGroup())
                    .channel(NioServerSocketChannel.class);
            bootstrap.childHandler(initializer);
            try {
                bootstrap.bind(address).sync();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Map<InetSocketAddress, ChannelInitializer<SocketChannel>> newChannelInitializers() {
        checkState(kafkaConfig != null);

        try {
            ImmutableMap.Builder<InetSocketAddress, ChannelInitializer<SocketChannel>> builder =
                ImmutableMap.builder();

            final Map<SecurityProtocol, EndPoint> advertisedEndpointMap =
                    EndPoint.parseListeners(kafkaConfig.getKafkaAdvertisedListeners());
            EndPoint.parseListeners(kafkaConfig.getListeners()).forEach((protocol, endPoint) -> {
                EndPoint advertisedEndPoint = advertisedEndpointMap.get(protocol);
                if (advertisedEndPoint == null) {
                    // Use the bind endpoint as the advertised endpoint.
                    advertisedEndPoint = endPoint;
                }
                switch (protocol) {
                    case PLAINTEXT:
                    case SASL_PLAINTEXT:
                        builder.put(endPoint.getInetAddress(), new KafkaProxyChannelInitializer(pulsarAdmin,
                                authenticationService, kafkaConfig, false,  advertisedEndPoint));
                        break;
                    case SSL:
                    case SASL_SSL:
                        builder.put(endPoint.getInetAddress(), new KafkaProxyChannelInitializer(pulsarAdmin,
                                authenticationService, kafkaConfig, true, advertisedEndPoint));
                        break;
                }
            });

            return builder.build();
        } catch (Exception e){
            log.error("KafkaProtocolHandler newChannelInitializers failed with ", e);
            return null;
        }
    }

    public void close() throws Exception {
        pulsarAdmin.close();
    }


}
