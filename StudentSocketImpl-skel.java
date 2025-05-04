import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private static final String CLOSED = "CLOSED";
  private static final String SYN_SENT = "SYN_SENT";
  private static final String LISTEN = "LISTEN";
  private static final String SYN_RCVD = "SYN_RCVD";
  private static final String ESTABLISHED = "ESTABLISHED";
  private static final String CLOSE_WAIT = "CLOSE_WAIT";
  private static final String LAST_ACK = "LAST_ACK";
  private static final String FIN_WAIT_1 = "FIN_WAIT_1";
  private static final String FIN_WAIT_2 = "FIN_WAIT_2";
  private static final String CLOSING = "CLOSING";
  private static final String TIME_WAIT = "TIME_WAIT";

  private PipedInputStream appIS;
  private PipedOutputStream appOS;
  private Demultiplexer D;
  private Timer tcpTimer;
  private String current_state;
  private int seqNum;
  private int ackNum;

  StudentSocketImpl(Demultiplexer D) {  // default constructor
    try {
      this.D = D;
      this.current_state = CLOSED;    
      this.appOS = new PipedOutputStream();
      this.appIS = new PipedInputStream(appOS);
      System.out.println("DEBUG: Student Socket initialized.");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) {
    try {
      localport = D.getNextAvailablePort();
      this.address = address;
      this.port = port;
      seqNum = 10; // change to randomize from 0-1000
      System.out.println("DEBUG: Variables initialized.");

      D.registerConnection(address, localport, port, this);
      System.out.println("DEBUG: Connection registered with " + address + " at " + port + " to local port " + localport);

      TCPPacket synPacket = new TCPPacket(localport, port, seqNum, 0, false, true, false, 1000, new byte[0]);
      System.out.println("DEBUG: TCPPacket created.");

      TCPWrapper.send(synPacket, address);
      changeState(SYN_SENT);
      System.out.println("DEBUG: packet sent.");

      System.out.println("SYN Packet sent to " + address + ":" + port);

      // Wait? until something
      long timeStart = System.currentTimeMillis();
      long timeout = 10000;

      while (current_state != ESTABLISHED) {
        long elapsed = System.currentTimeMillis() - timeStart;
        long timeLeft = timeout - elapsed;
        if (timeLeft <= 0) {
          throw new IOException("TCP Timeout from connectiong waiting to be established.");
        }
        try {
          wait();
        } catch (InterruptedException e) {
          throw new IOException("ERROR: Connection Interrupted", e);
        }
      }
      System.out.println("DEBUG: Connection established");

      new Thread(() -> {
        byte[] buf = new byte[512];
        int l;
        try {
          while ((l = appIS.read(buf)) != -1) {
              byte[] dataToSend = Arrays.copyOf(buf, l);
              TCPPacket dataPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1000, dataToSend);
              TCPWrapper.send(dataPacket, address);
              // Space for retransmission (if needed)
              seqNum = (seqNum + l) % TCPPacket.MAX_PACKET_SIZE;
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }).start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p) {
    System.out.println("Packet received: " + p);
    this.notifyAll();
    int packetLength = 1;
    switch(current_state) {
      case LISTEN:
        this.address = p.sourceAddr;

        this.port = p.sourcePort;

        this.seqNum = p.ackNum;

        packetLength = p.data != null? p.data.length : 1;
        this.ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;

        TCPPacket synAckPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 1000, new byte[0]);
        System.out.println("DEBUG: TCPPacket created.");

        TCPWrapper.send(synAckPacket, this.address);
        System.out.println("DEBUG: packet sent.");

        System.out.println("SYNACK Packet sent to " + this.address + ":" + port);

        changeState(SYN_RCVD);
        break;


      case SYN_SENT:

        this.seqNum = p.ackNum;

        packetLength = p.data != null? p.data.length : 1;
        this.ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;

        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1000, new byte[0]);
        System.out.println("DEBUG: TCPPacket created.");

        TCPWrapper.send(ackPacket, this.address);
        System.out.println("DEBUG: packet sent.");

        System.out.println("ACK Packet sent to " + this.address + ":" + port);

        changeState(ESTABLISHED);
        break;

      case SYN_RCVD:
        changeState(ESTABLISHED);
        break;

      case ESTABLISHED:
        if (p.data != null && p.data.length > 0) {
          try {
              // Push to Application
              appIS.connect(new PipedOutputStream() {{
                write(p.data);
                flush();
              }});
          } catch (IOException e) {
            e.printStackTrace();
          }
        
          // Send ACK
          packetLength = p.data.length;
          ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;
          TCPPacket ack = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1000, new byte[0]);
          TCPWrapper.send(ack, address);
        }

        break;
    }
  }

  private void changeState(String newState) {
    String previousState = this.current_state;
    this.current_state = newState;
    System.out.println("!!! " + previousState + "->" + newState);
    notifyAll();
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() {
    try {
      this.D.registerListeningSocket(localport, this);
      changeState(LISTEN);
      System.out.println("DEBUG: current_state changed to " + LISTEN + " with localport " + localport);

      long timeStart = System.currentTimeMillis();
      long timeout = 10000;

      while (current_state != ESTABLISHED && current_state != SYN_RCVD) {
        long elapsed = System.currentTimeMillis() - timeStart;
        long timeLeft = timeout - elapsed;
        if (timeLeft <= 0) {
          throw new IOException("TCP Timeout from connectiong waiting to be established.");
        }
        try {
          wait();
        } catch (InterruptedException e) {
          throw new IOException("ERROR: Connection Interrupted", e);
        }
      }
      System.out.println("DEBUG: Connection established");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  
  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() {
    // project 4 return appIS;
    return appIS;
    
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() {
    // project 4 return appOS;
    return appOS;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() {
    try {
      // TODO: close resources that need closing
      appIS.close();
      appOS.close();
    } catch (IOException e) {
      System.err.println("Error: Issue with closing streams: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /** 
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }


  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
  }
}
