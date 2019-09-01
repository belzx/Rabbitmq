package com.lizhi;

import com.rabbitmq.client.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Test1 {
    public static final String QUEUE_NAME = "queue_name";
    public static final String DEAD_QUEUE_NAME = "dead_queue_name";
    public static final String KEY_NAME = "key_name";
    public static final String DEAD_KEY_NAME = "dead_key_name";
    public static final String DEAD_EXCHANGE_NAME = "dead_exchange_name";
    public static final String EXCHANGE_NAME = "exchange_name";
    public static RabbitMQPro instance;

    @Before
    public void befor() {
        instance = RabbitMQPro.getInstance();
        DirectExchange directExchange = new DirectExchange(EXCHANGE_NAME, false, false);
        DirectExchange deadExchange = new DirectExchange(DEAD_EXCHANGE_NAME, false, false);
        instance.rabbitAdmin.declareExchange(directExchange);
        instance.rabbitAdmin.declareExchange(deadExchange);
        //create队列…
        HashMap<String, Object> hashMap = new HashMap<String, Object>() {
            {
                put("x-message-ttl", 60000);//闲置时长毫秒如果达到时长没有使
                put("x-max-length", 100);//man queue length
                put("x-max-priority", 10);//max priority
                put("x-dead-letter-exchange", DEAD_EXCHANGE_NAME);//过期的死信会转发
                put("x-dead-letter-routing-key", DEAD_KEY_NAME);//过期的死信会转发
                put("x-queue-mode", "lazy");//懒加载模式，消息一开始 会保存在磁盘上，不放在内存中
            }
        };
        //durable:开启持久化 exclusive 独占模式，只能同时一个。
        //autodelete如 果设置为true,并且有消费者成功连接，那么在最后一个消费者取消连接后，该Queue会自动删除自己
        Queue queue = new Queue(QUEUE_NAME, false, false, false, hashMap);//队列设置持持久hua
        //件对队列设置失效时间
        instance.rabbitAdmin.declareQueue(queue);
        instance.rabbitAdmin.declareQueue(new Queue(DEAD_QUEUE_NAME));
        //bang定队列到父换器，通路you键，声明路由键
        instance.rabbitAdmin.declareBinding(new Binding(QUEUE_NAME, Binding.DestinationType.QUEUE, EXCHANGE_NAME, KEY_NAME, new HashMap<>()));
        instance.rabbitAdmin.declareBinding(new Binding(DEAD_QUEUE_NAME, Binding.DestinationType.QUEUE, DEAD_EXCHANGE_NAME, DEAD_KEY_NAME, new HashMap<>()));
    }

    @After
    public void after() {
        instance.rabbitAdmin.deleteQueue(QUEUE_NAME);
        instance.rabbitAdmin.deleteQueue(DEAD_QUEUE_NAME);
        instance.rabbitAdmin.deleteExchange(EXCHANGE_NAME);
        instance.rabbitAdmin.deleteExchange(DEAD_EXCHANGE_NAME);
        instance.close();
    }

    @Test
    public void commonProducer() throws IOException {
        for (int i = 0; i < 10; i++) {
            instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, new AMQP.BasicProperties.Builder().expiration("6000").build(), "aaa".getBytes());
        }
    }

    /**
     * 消息延迟的实现
     */
    @Test
    public void delay() throws Exception {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType("text/x-json");//set xiaoxi geshi
        messageProperties.setDelay(10000);// 设置延迟进入队列的时间毫秒虽然提供了延迟队列的操作，但是好像不起作用
        messageProperties.setMessageId("123");//设置messageld
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);//设置消息持久化
        messageProperties.setPriority(10);//设置消息优先级，高的会被先消fei
        messageProperties.setExpiration("6000");//after 6s ,message will expire and will be allow to another queue
        Message message = new Message("aaa".getBytes(), messageProperties);
        long a = System.currentTimeMillis();
        instance.rabbitTemplate.send(EXCHANGE_NAME, KEY_NAME, message);
        Message receive = instance.rabbitTemplate.receive(DEAD_QUEUE_NAME, 500000);
        long b = System.currentTimeMillis();
        System.out.println("b-a:" + (b - a));
        System.out.println("message:" + new String(receive.getBody()));
    }

    /**
     * 事务的使用
     * 如果消息经过交换器进入队列
     * 就可以完成消息的持久化，但如果在进入broken之前出现意外，那就造成了消息的丢失，这个没有办法解决
     */
    @Test
    public void tras() throws Exception {
        try {
            //消息确认
            instance.rabbitTemplate.setChannelTransacted(true);//设置支持事务为在springboot框架使用
            instance.createChannel(true);
            instance.channel.txSelect();//开启事务
            instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, null, "aaa".getBytes());
            instance.channel.txCommit();//事务提交
        } catch (Exception e) {
            instance.channel.txRollback();//事务回滚
        }
    }

    /**
     * 事务的测试时间对比，事务与非事务相差最大250倍
     */
    @Test
    public void trans2() throws IOException {
        long a = System.currentTimeMillis();
        instance.createChannel(true);
        for (int i = 0; i < 1000; i++) {
            try {
                instance.channel.txSelect();//开启事务
                instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, null, "aaa".getBytes());
                instance.channel.txCommit();//事务提交
            } catch (Exception e) {
                instance.channel.txRollback();//事务rollback
            }
        }

        long b = System.currentTimeMillis();
        System.out.println(b - a);
        a = System.currentTimeMillis();
        instance.createChannel(false);
        for (int i = 0; i < 1000; i++) {
            instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, null, "aaa".getBytes());
        }
        b = System.currentTimeMillis();
        System.out.println(b - a);
    }

    /**
     * 事务性能非常差
     * 一般用确认模式
     * 当没有进入队列中的时候，则会会确认为false
     */
    @Test
    public void confirm() throws Exception {
        instance.channel.confirmSelect();
        //*普通确认模式
        instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, null, "aaa".getBytes());
        if (instance.channel.waitForConfirms(1000l)) {//等待被服务器确认进入队列
            System.out.print("全部执行 完成");
        }

        //批量确认模式
        for (int i = 0; i < 10; i++) {
            String message = String.format("时间 =>%s ", new Date().getTime());
            instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, null, message.getBytes("UTF-8"));
        }
        instance.channel.waitForConfirms();//直到所有信息都发布，只要有一个未确认就会IOException
        System.out.println("全部执行完成");

        // 异步监听确认和末确认的消息 式
        for (int i = 0; i < 10; i++) {
            String message = String.format("时间=>%s", new Date().getTime());
            instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME, null, message.getBytes("UTF-8"));
            instance.channel.addConfirmListener(new ConfirmListener() {
                @Override
                public void handleNack(long deliveryTag, boolean multiple) throws IOException {
                    System.out.println("未确认消息，标识：" + deliveryTag);
                }

                //这个确认的机制是有broken发送过来的
                @Override
                public void handleAck(long deliveryTag, boolean multiple) throws IOException {
                    System.out.println(String.format("已确认消息，标识：%d，多个消息：%b ", deliveryTag, multiple));
                }
            });
        }
        Thread.sleep(150000);
    }

    /**
     * mandatory为true时，交换器无法根据自身类型和路由键打开符合条件的队列时，会调用Basic.Return将消息返回给生产者
     * fasle 直接删除
     *
     * @throws Exception
     */

    @Test
    public void returnListener() throws Exception {
        instance.channel.addReturnListener(new ReturnListener() {
            @Override
            public void handleReturn(int replyCode, String replyText, String exchange, String routingkey, AMQP.BasicProperties properties, byte[] body) throws IOException {
                System.out.println(String.format("%s %s %s %s", replyCode, replyText, exchange, routingkey));
                for (int i = 0; i < 10; i++) {
                    String message = String.format("时间=>%s", new Date().getTime());
                    // ruguo mandatory wei true,则当 消息不能路由到队列中去的时候，出发retrunmethod
                    //如果位true，则server会删除这条消息
                    instance.channel.basicPublish(EXCHANGE_NAME, KEY_NAME + 213, true, null, message.getBytes("UTF-8"));
                }
            }
        });
        Thread.sleep(5000);
    }

    @Test
    public void comsumerNoAck() throws Exception {
        commonProducer();
        AtomicInteger atomcnteger = new AtomicInteger(0);
        Consumer defaultConsumer = new DefaultConsumer(instance.channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String message = new String(body, "utf-8");
                System.out.println("开始消费" + atomcnteger.get() + "：" + message);
                System.out.println(String.format("% s % s", consumerTag, envelope));
                if (atomcnteger.get() % 2 == 0) {
                    getChannel().basicRecover(true);//重新投递为true，则重新放到队列中，可以被当前再次消费，如果为true，自己欧盟呢哦其他人消费
                } else {
                    getChannel().basicAck(envelope.getDeliveryTag(), false);
                    System.out.println("completed comsumer " + atomcnteger.incrementAndGet());
                }

            }
        };
        // autoack ,如果任务一旦得到消息，则把任务自动ack，把任务消除，为false，则需要手动
        instance.channel.basicConsume(DEAD_EXCHANGE_NAME, false, defaultConsumer);
        Thread.sleep(1500000);
    }

    @Test
    public void comsumerAutoAck() throws Exception {
        commonProducer();
        AtomicInteger atomcnteger = new AtomicInteger(0);
        Consumer defaultConsumer = new DefaultConsumer(instance.channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String message = new String(body, "utf-8");
                System.out.println("开始消费" + atomcnteger.get() + "：" + message);
                System.out.println("消费完毕 " + atomcnteger.incrementAndGet());
            }
        };
        // autoack ,如果任务一旦得到消息，则把任务自动ack，把任务消除，为false，则需要手动
        instance.channel.basicConsume(DEAD_EXCHANGE_NAME, true, defaultConsumer);
        Thread.sleep(1500000);
    }
}

