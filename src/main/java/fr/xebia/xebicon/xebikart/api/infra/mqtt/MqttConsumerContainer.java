package fr.xebia.xebicon.xebikart.api.infra.mqtt;

import com.hivemq.client.internal.mqtt.datatypes.MqttUtf8StringImpl;
import com.hivemq.client.internal.mqtt.message.auth.MqttSimpleAuth;
import com.hivemq.client.internal.mqtt.message.auth.mqtt3.Mqtt3SimpleAuthView;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.Mqtt3Subscribe;
import fr.xebia.xebicon.xebikart.api.application.bus.EventReceiver;
import fr.xebia.xebicon.xebikart.api.application.bus.EventSource;
import fr.xebia.xebicon.xebikart.api.application.configuration.RabbitMqConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;

public class MqttConsumerContainer {

    private static final Logger LOGGER = getLogger(MqttConsumerContainer.class);

    private final RabbitMqConfiguration rabbitMqConfiguration;

    private final List<EventReceiver> eventReceivers;

    private final ExecutorService executorService;

    private Mqtt3BlockingClient mqttClient;

    private InternalPoller poller;

    public MqttConsumerContainer(
            RabbitMqConfiguration rabbitMqConfiguration,
            List<EventReceiver> eventReceivers,
            ExecutorService executorService
    ) {
        requireNonNull(rabbitMqConfiguration, "rabbitMqConfiguration must be defined.");
        requireNonNull(eventReceivers, "eventReceivers must be defined.");
        requireNonNull(executorService, "executorService must be defined.");
        this.rabbitMqConfiguration = rabbitMqConfiguration;
        this.eventReceivers = eventReceivers;
        this.executorService = executorService;
    }

    public synchronized void start() {
        if (mqttClient == null) {
            var mqttClientBuilder = Mqtt3Client.builder()
                    .identifier(UUID.randomUUID().toString())
                    .serverHost(rabbitMqConfiguration.getHost())
                    .serverPort(rabbitMqConfiguration.getPort());
            rabbitMqConfiguration.getCredentials().ifPresent(credentials -> {
                mqttClientBuilder.simpleAuth(
                        Mqtt3SimpleAuthView.of(
                                new MqttSimpleAuth(
                                        MqttUtf8StringImpl.of(credentials.getUsername()),
                                        ByteBuffer.wrap(credentials.getPassword().getBytes(StandardCharsets.UTF_8))
                                )
                        )
                );
            });

            mqttClient = mqttClientBuilder.buildBlocking();
            mqttClient.connect();
            LOGGER.info("Connected to MQTT server {}:{}", rabbitMqConfiguration.getHost(), rabbitMqConfiguration.getPort());

            rabbitMqConfiguration.getQueueNames().forEach(queueName -> {
                var subscribeMessage = Mqtt3Subscribe.builder()
                        .topicFilter(queueName)
                        .qos(MqttQos.EXACTLY_ONCE)
                        .build();
                LOGGER.info("Subscribing topic to {}", queueName);
                mqttClient.subscribe(subscribeMessage);
            });

            poller = new InternalPoller();
            executorService.submit(poller);

        }
    }

    private class InternalPoller implements Runnable {

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted() && isConnected()) {
                var publishes = mqttClient.publishes(MqttGlobalPublishFilter.ALL);
                LOGGER.trace("Waiting to received message.");
                try {
                    var optReceived = publishes.receive(10, TimeUnit.SECONDS);
                    optReceived.ifPresent(published -> {
                        var topic = published.getTopic().toString();
                        var payload = new String(published.getPayloadAsBytes());
                        if (StringUtils.isNotBlank(payload)) {
                            messageArrived(topic, payload);
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
        }

    }

    public synchronized boolean isConnected() {
        return mqttClient != null;
    }

    public synchronized void stop() {
        if (mqttClient != null) {
            LOGGER.info("Stopping MQTT client.");
            mqttClient.disconnect();
            mqttClient = null;
        }
    }

    private void messageArrived(String topic, String message) {
        requireNonNull(message, "message must be defined.");

        LOGGER.debug("-> MQTT [{}] : {}", topic, message);

        if (StringUtils.isNotBlank(message)) {
            var eventSource = new EventSource(topic, message);
            eventReceivers.forEach(eventReceiver -> {
                try {
                    eventReceiver.receive(eventSource);
                } catch (RuntimeException e) {
                    LOGGER.error("An error occurred while handling a message.", e);
                }
            });
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Received an empty payload MQTT message; ignoring it.");
        }
    }
}
