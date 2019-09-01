package com.lizhi;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.junit.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.util.HashMap;

public class Test3 {
    /**
     * CHANNEL模式,(类似connection单例模式)程序运行期间 ConnectionFactory会维护着一个 Connection,所有的操作都会使用 x个Connectin,但一个  Connection中可以有多个Channel,
     * 操作rabbitmq之前都必须先获取到一个Channel,否则就会阻塞
     * (可以通过setChannelCheckoutTimeout)设置 等待时间),这些Channel会被缓存
     * 人一好H国效其以理过、
     * setchanmnelcachesize)设置);
     * 过人中一120。
     * 这个模式下允许创建多个
     * Connection,会缓存一定数量的
     * CompeCuon,
     * 每个Connection中同样会缓存
     * 一些Channel,除了可以有多个
     * Connection,其它都跟CHANNEL模
     * 式样。
     * d.
     */

    @Test
    public void test() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setAddresses("localhost");
        connectionFactory.setPort(15672);
        connectionFactory.setCacheMode(CachingConnectionFactory.CacheMode.CONNECTION);
        connectionFactory.setConnectionCacheSize(2);
        Connection connection = connectionFactory.createConnection();
        Connection connection1 = connectionFactory.createConnection();
        System.out.println(connection);
        System.out.println(connection1);
    }

    @Test
    public void test2() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setAddresses("localhost");
        connectionFactory.setPort(15672);
        connectionFactory.setVirtualHost("test");
        /**
         * 对于每一个RabbitTemplate只支持一个ReturnCallback。
         *对于返回消息,模板的mandatory属性必须被设定为true,
         *它同样要求achingConnectionFactory的publisherReturns属性被设定为 true .如果客户端通过调用setReturnCallback(ReturnCallbackcallback)注册了 RabbitTemplate.ReturnCallback, 那么返回将被发送到客户端,这个回调函数必须实现下列方法：
         *void returnedMessage(Message message, intreplyCode, String replyText, String exchange, StringroutingKey);
         *connectionFactory.setPublisherReturns(true);
         *同样一个RabbitTemplate只支持一个Confirmcalback。
         *对于发布确认,template要求CachingConnectionFactory的 publisherConfirms属性设置为Mene
         *如果客户端通过setConfirmCallback(ConfirmCalbackcalback) 注册了RabbitTemplate.ConfirmCallback,那么确认消息将被发送到客户端。
         *这个回调函数必须实现以下
         方法：
         *void confirm (CorrelationData correlationData, booleanack);
         * connectionFactory.setPublisherConfirms(true);
         */

        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        //创建四种类型的Exchange 均为持久化,不自动删除
        // 点对点(单点：绑定queue时,设置routkey,发布消息时,设置routkey,单播)
        rabbitAdmin.declareExchange(new DirectExchange("direct.exchange", true, false));
        //定阅(绑定queue时,使用包 含 * 和#的表达式)
        rabbitAdmin.declareExchange(new TopicExchange("topic.exchange", true, false));
        //一对多(广播：绑定queue时,不用设置routkey.发布消息时,不用设置routkey)
        rabbitAdmin.declareExchange(new FanoutExchange("fanout.exchange", true, false));
        rabbitAdmin.declareExchange(new HeadersExchange("header.exchange", true, false));
        // 声明队列
        rabbitAdmin.declareQueue(new Queue("debug", true));
        rabbitAdmin.declareQueue(new Queue("info", true));
        rabbitAdmin.declareQueue(new Queue("infoer", true));
        //绑定队列到交换器,通过路由 键,声明路由键
        rabbitAdmin.declareBinding(new Binding("debug", Binding.DestinationType.QUEUE,
                "direct.exchange", "key.1", new HashMap()));
        rabbitAdmin.declareBinding(new Binding("info", Binding.DestinationType.QUEUE, "direct.exchange", " key.1", new HashMap()));
        rabbitAdmin.declareBinding(new Binding("", Binding.DestinationType.QUEUE, "direct.exchange", "key.2 ", new HashMap()));
        rabbitAdmin.declareBinding(new Binding("infoer", Binding.DestinationType.QUEUE, "direct.exchange", "key1", new HashMap()));
        //Spring AMQP提供了RabbitTemplate 来简化RabbitMQ 发送和接收消息操作
        AmqpTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        Message message = MessageBuilder.withBody("foo".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .setMessageId("123")
                .setHeader("bar", "baz")
                .build();
        //发送消息到默认的交换器。 从签送消息到默认的交换器,
        rabbitTemplate.send(message);
        //指定excahnge与key
        rabbitTemplate.send("direct.exchange", "key .1 ", message);
        rabbitTemplate.send("direct.exchange", " key .1 ", message);
        rabbitTemplate.send("direct.exchange", " key .2 ", message);
    }

    @Test
    public void consume() throws InterruptedException, IOException {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setAddresses("localhost");
        connectionFactory.setPort(15672);
        AmqpTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        Channel channel = ((RabbitTemplate) rabbitTemplate).getConnectionFactory().createConnection().createChannel(true);
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Message debug = rabbitTemplate.receive("info");
                System.out.println(debug);
            }
        }
        ).start();
        while (true) {
            Thread.sleep(2000);
            AMQP.Queue.DeclareOk info = channel.queueDeclarePassive("info");
            System.out.println(info.getMessageCount());
            System.out.println(info.getConsumerCount());
        }
    }
}