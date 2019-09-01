# client源码接受任务源码分析
###1；connection 注册到了borken
###2：broken发布消息到client上,mainloep acceept接受到消息handleFrame）->processAsync()
~~~
 // com.rabbitmq.client.impl.AMQConnection
  private class MainLoop implements Runnable {

        /**
         * Channel reader thread main loop. Reads a frame, and if it is
         * not a heartbeat frame, dispatches it to the channel it refers to.
         * Continues running until the "running" flag is set false by
         * shutdown().
         */
        public void run() {
            try {
                while (_running) {
                    Frame frame = _frameHandler.readFrame();

                    if (frame != null) {
                        _missedHeartbeats = 0;
                        if (frame.type == AMQP.FRAME_HEARTBEAT) {
                            // Ignore it: we've already just reset the heartbeat counter.
                        } else {
                            if (frame.channel == 0) { // the special channel
                                _channel0.handleFrame(frame);
                            } else {
                                if (isOpen()) {
                                    // If we're still _running, but not isOpen(), then we
                                    // must be quiescing, which means any inbound frames
                                    // for non-zero channels (and any inbound commands on
                                    // channel zero that aren't Connection.CloseOk) must
                                    // be discarded.
                                    ChannelManager cm = _channelManager;
                                    if (cm != null) {
                                        cm.getChannel(frame.channel).handleFrame(frame); //这里开始处理任务
                                    }
                                }
                            }
                        }
                    } else {
                        // Socket timeout waiting for a frame.
                        // Maybe missed heartbeat.
                        handleSocketTimeout();
                    }
                }
            } catch (EOFException ex) {
                if (!_brokerInitiatedShutdown)
                    shutdown(null, false, ex, true);
            } catch (Throwable ex) {
                _exceptionHandler.handleUnexpectedConnectionDriverException(AMQConnection.this,
                                                                            ex);
                shutdown(null, false, ex, true);
            } finally {
                // Finally, shut down our underlying data connection.
                _frameHandler.close();
                _appContinuation.set(null);
                notifyListeners();
            }
        }
    }
~~~
###4：先从processAsync（）开始看，这个下面开始就是分发和处理
~~~
  /**
     * Protected API - Filters the inbound command stream, processing
     * Basic.Deliver, Basic.Return and Channel.Close specially.  If
     * we're in quiescing mode, all inbound commands are ignored,
     * except for Channel.Close and Channel.CloseOk.
     */
    @Override public boolean processAsync(Command command) throws IOException
    {
        // If we are isOpen(), then we process commands normally.
        //
        // If we are not, however, then we are in a quiescing, or
        // shutting-down state as the result of an application
        // decision to close this channel, and we are to discard all
        // incoming commands except for a close and close-ok.

        Method method = command.getMethod();
        // we deal with channel.close in the same way, regardless
        if (method instanceof Channel.Close) {
            asyncShutdown(command);
            return true;
        }

        if (isOpen()) {
            // We're in normal running mode.

            if (method instanceof Basic.Deliver) {
                processDelivery(command, (Basic.Deliver) method);//根据不同的类型下发不同 的任务
                return true;
            } else if (method instanceof Basic.Return) {
                callReturnListeners(command, (Basic.Return) method);
                return true;
            } else if (method instanceof Channel.Flow) {
                Channel.Flow channelFlow = (Channel.Flow) method;
                synchronized (_channelMutex) {
                    _blockContent = !channelFlow.getActive();
                    transmit(new Channel.FlowOk(!_blockContent));
                    _channelMutex.notifyAll();
                }
                callFlowListeners(command, channelFlow);
                return true;
            } else if (method instanceof Basic.Ack) {
                Basic.Ack ack = (Basic.Ack) method;
                callConfirmListeners(command, ack);
                handleAckNack(ack.getDeliveryTag(), ack.getMultiple(), false);
                return true;
            } else if (method instanceof Basic.Nack) {
                Basic.Nack nack = (Basic.Nack) method;
                callConfirmListeners(command, nack);
                handleAckNack(nack.getDeliveryTag(), nack.getMultiple(), true);
                return true;
            } else if (method instanceof Basic.RecoverOk) {
                for (Map.Entry<String, Consumer> entry : _consumers.entrySet()) {
                    this.dispatcher.handleRecoverOk(entry.getValue(), entry.getKey());
                }
                // Unlike all the other cases we still want this RecoverOk to
                // be handled by whichever RPC continuation invoked Recover,
                // so return false
                return false;
            } else if (method instanceof Basic.Cancel) {
                Basic.Cancel m = (Basic.Cancel)method;
                String consumerTag = m.getConsumerTag();
                Consumer callback = _consumers.remove(consumerTag);
                if (callback == null) {
                    callback = defaultConsumer;
                }
                if (callback != null) {
                    try {
                        this.dispatcher.handleCancel(callback, consumerTag);
                    } catch (Throwable ex) {
                        getConnection().getExceptionHandler().handleConsumerException(this,
                                                                                      ex,
                                                                                      callback,
                                                                                      consumerTag,
                                                                                      "handleCancel");
                    }
                }
                return true;
            } else {
                return false;
            }
        } else {
            // We're in quiescing mode == !isOpen()

            if (method instanceof Channel.CloseOk) {
                // We're quiescing, and we see a channel.close-ok:
                // this is our signal to leave quiescing mode and
                // finally shut down for good. Let it be handled as an
                // RPC reply one final time by returning false.
                return false;
            } else {
                // We're quiescing, and this inbound command should be
                // discarded as per spec. "Consume" it by returning
                // true.
                return true;
            }
        }
    }

~~~
###5：dispacher其实就是一个分发的类，类似于中转站，以及决定了
~~~
  if (method instanceof Basic.Deliver) {
                processDelivery(command, (Basic.Deliver) method);
                return true;
~~~
~~~
   this.dispatcher.handleDelivery(callback,
                                           m.getConsumerTag(),
                                           envelope,
                                           (BasicProperties) command.getContentHeader(),
                                           command.getContentBody());
~~~
###6：这里就是分发的核心代码，这里会把消息分配到queue里面
~~~

    private void executeUnlessShuttingDown(Runnable r) {
        if (!this.shuttingDown) execute(r);
    }

    private void execute(Runnable r) {
        checkShutdown();
        this.workService.addWork(this.channel, r);
    }
    
    public void addWork(Channel channel, Runnable runnable) {
            if (this.workPool.addWorkItem(channel, runnable)) {
                this.executor.execute(new WorkPoolRunnable());
            }
    }
~~~

addWorkItem（）如果为false，不要紧，因为线程会持续处理这个queue
####7：每一个Channel对应一个Queue，消息会根据channel put到对应的queue中，然后启动将 WOTKPOONUUNGDISAOSIUELKEy
~~~

    private final class WorkPoolRunnable implements Runnable {

        public void run() {
            int size = MAX_RUNNABLE_BLOCK_SIZE;
            List<Runnable> block = new ArrayList<Runnable>(size);
            try {
                Channel key = ConsumerWorkService.this.workPool.nextWorkBlock(block, size);/
                if (key == null) return; // nothing ready to run
                try {
                    for (Runnable runnable : block) {//遍历所有的任务
                        runnable.run();
                    }
                } finally {
                    if (ConsumerWorkService.this.workPool.finishWorkBlock(key)) { //如果queue中还有未处理的任务，这里会去判断，则继续exceute线程
                        ConsumerWorkService.this.executor.execute(new WorkPoolRunnable());
                    }
                }
            } catch (RuntimeException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    
~~~
