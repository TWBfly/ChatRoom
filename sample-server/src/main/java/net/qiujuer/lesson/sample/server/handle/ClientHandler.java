package net.qiujuer.lesson.sample.server.handle;



import net.qiujuer.library.clink.core.Connector;
import net.qiujuer.library.clink.utils.CloseUtils;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientHandler {
    private final SocketChannel socketChannel;
    private final ClientWriteHandler writeHandler;
    private final ClientHandlerCallback clientHandlerCallback;
    private final String clientInfo;
    private final Connector connector;

    public ClientHandler(SocketChannel socketChannel, ClientHandlerCallback clientHandlerCallback) throws IOException {
        this.socketChannel = socketChannel;
        connector = new Connector() {
            @Override
            public void onChannelClosed(SocketChannel channel) {
                super.onChannelClosed(channel);
                exitBySelf();
            }

            @Override
            protected void onReceiveNewMessage(String str) {
                super.onReceiveNewMessage(str);
                clientHandlerCallback.onNewMessageArrived(ClientHandler.this,str);
            }
        };
        connector.setup(socketChannel);



        //写
        Selector writeSelector = Selector.open();
        socketChannel.register(writeSelector, SelectionKey.OP_WRITE);
        this.writeHandler = new ClientWriteHandler(writeSelector);

        this.clientHandlerCallback = clientHandlerCallback;
        this.clientInfo = socketChannel.getRemoteAddress().toString();//服务端地址
        System.out.println("新客户端连接：" + clientInfo);
    }

    public String getClientInfo() {
        return clientInfo;
    }

    public void exit() {
        writeHandler.exit();
        CloseUtils.close(socketChannel);
        System.out.println("客户端已退出：" + clientInfo);
    }

    public void send(String str) {
        writeHandler.send(str);
    }

    private void exitBySelf() {
        exit();
        clientHandlerCallback.onSelfClosed(this);
    }

    public interface ClientHandlerCallback {
        //自身关闭通知
        void onSelfClosed(ClientHandler handler);

        //收到消息时通知
        void onNewMessageArrived(ClientHandler handler, String msg);
    }

    class ClientReadHandler extends Thread {
        private final Selector selector;
        private final ByteBuffer byteBuffer;
        private boolean done = false;

        ClientReadHandler(Selector selector) {
            this.selector = selector;
            this.byteBuffer = ByteBuffer.allocate(256);
        }

        @Override
        public void run() {
            super.run();
            try {
                do {
                    // 客户端拿到一条数据
                    if (selector.select() == 0){
                        if (done){
                            break;
                        }
                        continue;
                    }

                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()){
                        if (done){
                            break;
                        }
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        if (key.isReadable()){
                            SocketChannel client = (SocketChannel) key.channel();
                            byteBuffer.clear();
                            int read = client.read(byteBuffer);
                            if (read>0){
                                //丢弃换行符
                                String str = new String(byteBuffer.array(), 0, read - 1);
                                clientHandlerCallback.onNewMessageArrived(ClientHandler.this, str);
                            }else {
                                System.out.println("客户端已无法读取数据！");
                                // 退出当前客户端
                                ClientHandler.this.exitBySelf();
                                break;
                            }
                        }
                    }
                } while (!done);
            } catch (Exception e) {
                if (!done) {
                    System.out.println("连接异常断开");
                    ClientHandler.this.exitBySelf();
                }
            } finally {
                // 连接关闭
                CloseUtils.close(selector);
            }
        }

        void exit() {
            done = true;
            selector.wakeup();
            CloseUtils.close(selector);
        }
    }

    class ClientWriteHandler {
        private boolean done = false;
        private final Selector selector;
        private final ByteBuffer byteBuffer;
        private final ExecutorService executorService;

        ClientWriteHandler(Selector selector) {
            this.selector = selector;
            byteBuffer = ByteBuffer.allocate(256);
            this.executorService = Executors.newSingleThreadExecutor();
        }

        void exit() {
            done = true;
            CloseUtils.close(selector);
            executorService.shutdownNow();
        }

        void send(String str) {
            if (done){
                return;
            }
            executorService.execute(new WriteRunnable(str));
        }

        class WriteRunnable implements Runnable {
            private final String msg;

            WriteRunnable(String msg) {
                this.msg = msg + '\n';
            }

            @Override
            public void run() {
                if (ClientWriteHandler.this.done) {
                    return;
                }
                byteBuffer.clear();
                byteBuffer.put(msg.getBytes());
                byteBuffer.flip();//反转 将指针恢复到初始位置
                while (!done&& byteBuffer.hasRemaining()){
                    try {
                        int len = socketChannel.write(byteBuffer);
                        //len=0是合法的
                        if (len<0){
                            System.out.printf("客户端无法发送数据--");
                            ClientHandler.this.exitBySelf();
                            break;
                        }


                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

            }
        }
    }
}
