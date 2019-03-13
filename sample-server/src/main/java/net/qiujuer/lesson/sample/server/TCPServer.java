package net.qiujuer.lesson.sample.server;

import net.qiujuer.lesson.sample.server.handle.ClientHandler;
import net.qiujuer.library.clink.utils.CloseUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TCPServer implements ClientHandler.ClientHandlerCallback {
    private final int port;
    private ClientListener mListener;
    private List<ClientHandler> clientHandlerList = new ArrayList<>();
    private final ExecutorService forwardingThreadPoolExecutor;
    private Selector selector;
    private ServerSocketChannel server;

    public TCPServer(int port) {
        this.port = port;
        //转发线程池
        forwardingThreadPoolExecutor = Executors.newSingleThreadExecutor();
    }

    public boolean start() {
        try {
            selector = Selector.open();
            server = ServerSocketChannel.open();
            server.configureBlocking(false);//设置非阻塞
            server.socket().bind(new InetSocketAddress(port));//绑定本地端口
            server.register(selector, SelectionKey.OP_ACCEPT);//注册客户端连接到达监听
            System.out.println("服务器信息：" + server.getLocalAddress().toString());
            //启用客户端监听
            ClientListener listener = new ClientListener();
            mListener = listener;
            listener.start();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void stop() {
        if (mListener != null) {
            mListener.exit();
        }
        CloseUtils.close(server);
        CloseUtils.close(selector);

        synchronized (TCPServer.this) {
            for (ClientHandler clientHandler : clientHandlerList) {
                clientHandler.exit();
            }
            clientHandlerList.clear();
        }
        //停止线程池
        forwardingThreadPoolExecutor.shutdownNow();
    }

    public synchronized void broadcast(String str) {
        for (ClientHandler clientHandler : clientHandlerList) {
            clientHandler.send(str);
        }
    }

    @Override
    public synchronized void onSelfClosed(ClientHandler handler) {
        clientHandlerList.remove(handler);
    }

    @Override
    public void onNewMessageArrived(ClientHandler handler, String msg) {
        System.out.println("收到—"+handler.getClientInfo()+"--"+msg);
        //异步提交转发任务
        forwardingThreadPoolExecutor.execute(() -> {
            synchronized (TCPServer.this){
                for (ClientHandler clientHandler:clientHandlerList) {
                    if (clientHandler.equals(handler)){
                        //跳过自己
                        continue;
                    }
                    //对其它客户端发送消息
                    clientHandler.send(msg);
                }
            }
        });



    }

    private class ClientListener extends Thread {
        private boolean done = false;

        @Override
        public void run() {
            super.run();

            System.out.println("服务器准备就绪～");
            // 等待客户端连接
            do {
                // 得到客户端
                try {
                    if(selector.select() == 0){
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
                        //检查当前key的状态是否是我们关注的
                        //客户端到达状态
                        if (key.isAcceptable()){
                            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
                            //非阻塞状态拿到客户端连接
                            SocketChannel socketChannel = serverSocketChannel.accept();
                            try {
                                // 客户端构建异步线程
                                ClientHandler clientHandler = new ClientHandler(socketChannel, TCPServer.this);
                                //添加同步处理
                                synchronized (TCPServer.this) {
                                    clientHandlerList.add(clientHandler);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                                System.out.println("客户端连接异常：" + e.getMessage());
                            }

                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

            } while (!done);

            System.out.println("服务器已关闭！");
        }

        void exit() {
            done = true;
            //取消阻塞状态 唤醒当前阻塞
            selector.wakeup();
        }
    }
}
