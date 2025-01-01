package main.java;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

import com.google.protobuf.InvalidProtocolBufferException;

import main.java.MessageOuterClass.Message;  // Importação da classe Message gerada pelo Protobuf

public class Gateway {
    public static final int CLIENT_PORT = 4000; // Porta para o Client
    public static final int SENSOR_PORT = 5000; // Porta para os Sensores
    public static final String HOSTNAME = "127.0.0.1";
    public static final String MULTICAST_GROUP = "230.0.0.0";
    public static final int MULTICAST_PORT = 6000; // Porta para a comunicação multicast

    private ServerSocketChannel sensorServerChannel;
    private ServerSocketChannel clientServerChannel;
    private List<SocketChannel> sensors = Collections.synchronizedList(new ArrayList<>());
    private List<SocketChannel> clients = Collections.synchronizedList(new ArrayList<>());

    // ThreadPool para lidar com múltiplas conexões de sensores
    private ExecutorService sensorThreadPool = Executors.newFixedThreadPool(10); // Até 10 sensores simultâneos

    public Gateway() throws IOException {
        // Configura o servidor para sensores
        sensorServerChannel = ServerSocketChannel.open();
        sensorServerChannel.configureBlocking(true);
        sensorServerChannel.bind(new InetSocketAddress(HOSTNAME, SENSOR_PORT));
        System.out.println("Servidor Sensor TCP iniciado no endereço " + HOSTNAME + " na porta " + SENSOR_PORT);

        // Configura o servidor para clientes
        clientServerChannel = ServerSocketChannel.open();
        clientServerChannel.configureBlocking(true);
        clientServerChannel.bind(new InetSocketAddress(HOSTNAME, CLIENT_PORT));
        System.out.println("Servidor Client TCP iniciado no endereço " + HOSTNAME + " na porta " + CLIENT_PORT);
    }

    public void start() throws IOException {
        // Inicia a thread de multicast para enviar mensagens periódicas aos sensores
        new Thread(this::sendMulticast).start();

        // Aceita conexões dos Sensores
        new Thread(this::acceptSensors).start();

        // Aceita conexões dos Clients
        new Thread(this::acceptClients).start();
    }

    private void sendMulticast() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);

            // Mensagem que os sensores irão receber para saber como se conectar ao Gateway
            String message = HOSTNAME + ":" + SENSOR_PORT;

            // Envia a mensagem multicast a cada 5 segundos
            while (true) {
                DatagramPacket packet = new DatagramPacket(
                        message.getBytes(),
                        message.length(),
                        group,
                        MULTICAST_PORT
                );
                socket.send(packet);
                System.out.println("Mensagem de multicast enviada: " + message);
                Thread.sleep(5000); // Aguarda 5 segundos antes de enviar novamente
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Erro ao enviar multicast: " + e.getMessage());
        }
    }

    private void acceptSensors() {
        try {
            while (true) {
                SocketChannel sensorChannel = sensorServerChannel.accept(); // Bloqueia e espera próximo sensor
                System.out.println("Sensor TCP " + sensorChannel.getRemoteAddress() + " conectado.");
                sensors.add(sensorChannel);

                // Envia para o ThreadPool para tratamento paralelo
                sensorThreadPool.submit(() -> handleSensor(sensorChannel));
            }
        } catch (IOException e) {
            System.out.println("Erro ao aceitar conexão de Sensor: " + e.getMessage());
        }
    }

    private void handleSensor(SocketChannel sensorChannel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 2);
            while (true) {
                buffer.clear();  // Limpar o buffer antes de cada leitura
                int bytesRead = sensorChannel.read(buffer);
                if (bytesRead == -1) {
                    break;  // Conexão fechada
                }

                buffer.flip();  // Prepara o buffer para leitura
                if (buffer.remaining() >= 4) {
                    int messageLength = buffer.getInt();  // Lê o comprimento da mensagem
                    if (buffer.remaining() < messageLength) {
                        // Se o buffer não contém a mensagem completa, aguarde mais dados
                        buffer.position(buffer.limit()); // Faz o buffer avançar
                        continue;
                    }

                    byte[] data = new byte[messageLength];
                    buffer.get(data);  // Lê a mensagem completa

                    try {
                        Message message = Message.parseFrom(data);  // Desserializa a mensagem
                        System.out.println("Mensagem recebida do Sensor: " + message);
                        forwardToClients(message);
                    } catch (InvalidProtocolBufferException e) {
                        System.out.println("Erro ao desserializar mensagem do Sensor: " + e.getMessage());
                    }
                }
                buffer.clear();  // Limpa o buffer para a próxima leitura
            }
        } catch (IOException e) {
            System.out.println("Erro ao comunicar com o Sensor: " + e.getMessage());
        } finally {
            try {
                sensorChannel.close();
            } catch (IOException e) {
                System.out.println("Erro ao fechar conexão com o Sensor: " + e.getMessage());
            }
        }
    }

    private void acceptClients() {
        try {
            while (true) {
                SocketChannel clientChannel = clientServerChannel.accept();
                System.out.println("Client TCP " + clientChannel.getRemoteAddress() + " conectado.");
                clients.add(clientChannel);

                // Trata clientes em uma nova thread
                new Thread(() -> handleClient(clientChannel)).start();
            }
        } catch (IOException e) {
            System.out.println("Erro ao aceitar conexão de Client: " + e.getMessage());
        }
    }

    private void handleClient(SocketChannel clientChannel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 2);
            while (true) {
                buffer.clear();  // Limpar o buffer antes de cada leitura
                int bytesRead = clientChannel.read(buffer);
                if (bytesRead == -1) {
                    break;  // Conexão fechada
                }

                buffer.flip();  // Prepara o buffer para leitura
                if (buffer.remaining() >= 4) {
                    int messageLength = buffer.getInt();  // Lê o comprimento da mensagem
                    if (buffer.remaining() < messageLength) {
                        // Se o buffer não contém a mensagem completa, aguarde mais dados
                        buffer.position(buffer.limit()); // Faz o buffer avançar
                        continue;
                    }

                    byte[] data = new byte[messageLength];
                    buffer.get(data);  // Lê a mensagem completa

                    try {
                        Message message = Message.parseFrom(data);  // Desserializa a mensagem
                        System.out.println("Mensagem recebida do Client: " + message);
                        forwardToSensors(message);
                    } catch (InvalidProtocolBufferException e) {
                        System.out.println("Erro ao desserializar mensagem do Client: " + e.getMessage());
                    }
                }
                buffer.clear();  // Limpa o buffer para a próxima leitura
            }
        } catch (IOException e) {
            System.out.println("Erro ao comunicar com o Client: " + e.getMessage());
        } finally {
            try {
                clientChannel.close();
            } catch (IOException e) {
                System.out.println("Erro ao fechar conexão com o Client: " + e.getMessage());
            }
        }
    }

    private void forwardToSensors(Message message) {
        synchronized (sensors) {
            if (sensors.isEmpty()) {
                System.out.println("Nenhum sensor conectado. Não é possível encaminhar a mensagem.");
            }
            for (SocketChannel sensorChannel : sensors) {
                try {
                    System.out.println("Tentando repassar mensagem para o Sensor: " + sensorChannel.getRemoteAddress());
                    byte[] messageBytes = message.toByteArray();
                    ByteBuffer buffer = ByteBuffer.allocate(4 + messageBytes.length);
                    buffer.putInt(messageBytes.length); // Tamanho da mensagem
                    buffer.put(messageBytes); // Mensagem Protobuf
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        sensorChannel.write(buffer);  // Envia a mensagem Protobuf para o Sensor
                    }
                    System.out.println("Mensagem do Gateway repassada para o Sensor: " + message);
                } catch (IOException e) {
                    System.out.println("Erro ao repassar mensagem para o Sensor: " + e.getMessage());
                }
            }
        }
    }

    private void forwardToClients(Message message) {
        synchronized (clients) {
            for (SocketChannel clientChannel : clients) {
                try {
                    // Serializa a mensagem para enviar ao cliente
                    byte[] messageBytes = message.toByteArray();
                    ByteBuffer buffer = ByteBuffer.allocate(4 + messageBytes.length);
                    buffer.putInt(messageBytes.length); // Tamanho da mensagem
                    buffer.put(messageBytes); // Mensagem Protobuf
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        clientChannel.write(buffer);  // Envia a mensagem Protobuf para o Client
                    }
                    System.out.println("Mensagem do Gateway repassada para o Client: " + message);
                } catch (IOException e) {
                    System.out.println("Erro ao repassar mensagem para o Client: " + e.getMessage());
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            Gateway server = new Gateway();
            server.start();
        } catch (IOException e) {
            System.err.println("Erro ao inicializar servidor: " + e.getMessage());
        }
    }
}
