package com.lizhi;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.amqp.core.Queue;

import java.io.IOException;

/**
 * 6种使用模式
 */
public class Test2 {
    Test1 test1;
    RabbitMQPro instance;

    @Before
    public void before() throws IOException {
        test1 = new Test1();
        test1.befor();
        instance = Test1.instance;
    }

    @After
    public void after() {
        test1.after();
    }

    /*
     1:单单queue
     2：一个生产一个消费
     3：一个生产者，多个消费者，
     4：发布订阅，一个生产者、一
     个交换机、多个队列、多个消费者
     5：路由模式
     6：rpc
     */
    @Test
    public void Model2() throws IOException, InterruptedException {
        //一个生产者一个消费者略
        test1.commonProducer();
        instance.channel.basicQos(1);//一次 一条，如果有消息没有被ack，则不会 被继续下发下来
        DefaultConsumer defaultConsumer = new DefaultConsumer(instance.channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String message = new String(body, "UTF-8");
                System.out.println("开始等待被消费：" + message + Thread.currentThread().getName());//这个处理过程实际上是线程池在主持
                try {
                    Thread.sleep(12000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                System.out.println("开始消费：" + message + Thread.currentThread().getName());
                System.out.println(String.format("%s % s", consumerTag, envelope));
                // 这个处理过程实际上是线程池在主持
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                getChannel().basicAck(envelope.getDeliveryTag(), false);
                System.out.println("结束消费：" + message + Thread.currentThread().getName());
            }
        };
        instance.channel.basicConsume(Test1.DEAD_QUEUE_NAME, false, defaultConsumer);
        System.out.println("1");
        Thread.sleep(2000000);
    }

    @Test
    public void Model3() throws IOException, InterruptedException {
        //一个生产者多个消费者
        test1.commonProducer();
        RabbitMQPro a = RabbitMQPro.getInstance();
        RabbitMQPro b = RabbitMQPro.getInstance();
        DefaultConsumer defaultConsumer = new DefaultConsumer(a.channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String message = new String(body, "UTF-8");
                System.out.println("开始消费：" + message + Thread.currentThread().getName());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                getChannel().basicAck(envelope.getDeliveryTag(), false);
                System.out.println("结束消费：" + message + Thread.currentThread().getName());
            }
        };
        DefaultConsumer defaultConsumer2 = new DefaultConsumer(a.channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String message = new String(body, "UTF-8");
                System.out.println("开始消费：" + message + Thread.currentThread().getName());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                getChannel().basicAck(envelope.getDeliveryTag(), false);
                System.out.println("结束消费：" + message + Thread.currentThread().getName());
            }
        };

        a.channel.basicQos(1);
        b.channel.basicQos(1);
        a.channel.basicConsume(Test1.DEAD_QUEUE_NAME, false, defaultConsumer);
        b.channel.basicConsume(Test1.DEAD_QUEUE_NAME, false, defaultConsumer2);
        System.out.println("1");
        Thread.sleep(2000000);
    }

    public static final String RPC_NAME = "rpc_name";
    public static final String RPC_RECALL_NAME = "rpc_recallname";

    @Test
    public void testRpcServer() throws Exception {
        RabbitMQPro instance = RabbitMQPro.getInstance();
        instance.rabbitAdmin.declareQueue(new Queue(RPC_NAME));
        instance.channel.basicQos(1);
        DefaultConsumer defaultConsumer = new DefaultConsumer(instance.channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                String callQueueName = properties.getReplyTo();//指定回调队列的名称
                String message = new String(body, "UTF-8");
                int fib = fib(Integer.valueOf(message));
                instance.channel.basicPublish("", callQueueName, properties, String.valueOf(fib).getBytes());
                instance.channel.basicAck(envelope.getDeliveryTag(), false);

            }
        };
        while (true) {
            instance.channel.basicConsume(RPC_NAME, false, defaultConsumer);
            Thread.sleep(5555555);
        }
    }

    /**
     * 计算斐波列其数列的第n项，40max
     *
     * @param i
     * @return
     */
    public static int fib(int i) {
        if (i <= 0)
            return 0;
        if (i == 1)
            return 1;
        if (i == 2)
            return 2;
        return fib(i - 1) + fib(i - 2);
    }

    @Test
    public void testRpcClient() throws Exception {
        RabbitMQPro instance = RabbitMQPro.getInstance();
        instance.rabbitAdmin.declareQueue(new Queue(RPC_RECALL_NAME));
        while (true) {
            int put = (int) (Math.random() * 40);
            AMQP.BasicProperties properties = new AMQP.BasicProperties().builder().
                    clusterId(System.currentTimeMillis() + "" + Math.random()).replyTo(RPC_RECALL_NAME).build();// 指定回调队列的名称
            instance.channel.basicPublish("", RPC_NAME, properties, String.valueOf(put).getBytes());
            DefaultConsumer defaultConsumer = new DefaultConsumer(instance.channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    String message = new String(body, "UTF-8");
                    System.out.println("接受到的回调的结果为:" + message);
                    instance.channel.basicAck(envelope.getDeliveryTag(), false);
                }
            };
            instance.channel.basicConsume(RPC_RECALL_NAME, false, defaultConsumer);
            Thread.sleep(10000);
        }
    }

    /**
     * 这个是一个高吞吐的消费模式
     * get是只取了队列里面的第一条消忌。
     * 因为get开销大，如果需要从一个队列取消息的话，首选consume方式，慎用循环get方式。
     */

    @Test
    public void basicGet() throws
            IOException, InterruptedException {
        test1.commonProducer();
        while (true) {
            //basicget会去尝试的获取消 息信息。如果没有则返回为false
            System.out.println(instance.channel.basicGet(Test1.DEAD_QUEUE_NAME, false));
            Thread.sleep(5000);
        }

    }

}