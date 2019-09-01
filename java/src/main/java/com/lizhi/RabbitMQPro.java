package com.lizhi;


import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;

public class RabbitMQPro {
    CachingConnectionFactory connectionFactory;
    RabbitTemplate rabbitTemplate;
    Channel channel;
    RabbitAdmin rabbitAdmin;

    public static RabbitMQPro getInstance() {
        return new RabbitMQPro().createConnectionFactory().createRabbitAdmin().createChannel().createRabbitTemplate();
    }

    public RabbitMQPro createConnectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setAddresses("localhost");
        connectionFactory.setPort(5672);
        connectionFactory.setUsername("geust");
        connectionFactory.setPassword("geust");
        this.connectionFactory = connectionFactory;
        return this;
    }


    public RabbitMQPro createChannel() {
        connectionFactory.createConnection().createChannel(false);
        return this;
    }

    public RabbitMQPro createChannel(boolean tran) {
        this.channel = connectionFactory.createConnection().createChannel(tran);
        return this;
    }

    public RabbitMQPro createRabbitAdmin() {
        this.rabbitAdmin = new RabbitAdmin(connectionFactory);
        return this;
    }


    public RabbitMQPro createRabbitTemplate() {
        this.rabbitTemplate = new
                RabbitTemplate(connectionFactory);
        return this;
    }

    public void close() {
        try {
            this.channel.close();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }
}
