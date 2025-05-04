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
      // Set up the connection
      localport = D.getNextAvailablePort();
      this.address = address;
      this.port = port;
      seqNum = 10; // change to randomize from 0-1000
      // System.out.println("DEBUG: Variables initialized.");

      D.registerConnection(address, localport, port, this);
      // System.out.println("DEBUG: Connection registered with " + address + " at " + port + " to local port " + localport);

      // Create and send a SYN packet to the target host
      TCPPacket synPacket = new TCPPacket(localport, port, seqNum, 0, false, true, false, 1000, new byte[0]);
      // System.out.println("DEBUG: TCPPacket created.");

      TCPWrapper.send(synPacket, address);
      changeState(SYN_SENT);
      // System.out.println("DEBUG: packet sent.");

      // System.out.println("SYN Packet sent to " + address + ":" + port);

      // Wait until SYN+ACK received
      long timeStart = System.currentTimeMillis();
      long timeout = 10000;

      while (! current_state.equals(ESTABLISHED)) {
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
      // System.out.println("DEBUG: Connection established");
      
      // Use the connection to send data
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

      // Server is currently awaiting first SYN from client
      // Upon receiving a SYN, send SYN+ACK and switch to SYN_RCVD state
      case LISTEN:

        // Check if packet is a SYN; should ignore if not SYN
        if (! p.synFlag) {
          System.err.println("DEBUG: Packet received during state LISTEN but it was not a SYN packet.");
          break;
        }

        // Get packet information and flags
        this.address = p.sourceAddr;
        this.port = p.sourcePort;
        this.seqNum = p.ackNum;
        packetLength = p.data != null? p.data.length : 20;
        this.ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;

        // Set up connection and close the listening socket
        try {
          this.D.unregisterListeningSocket(localport, this);
          this.D.registerConnection(address, localport, port, this);
        } catch (IOException e) {
          e.printStackTrace();
        }

        // Create and send SYN+ACK
        TCPPacket synAckPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 1000, new byte[0]);
        // System.out.println("DEBUG: TCPPacket created.");

        TCPWrapper.send(synAckPacket, this.address);
        // System.out.println("DEBUG: packet sent.");

        System.out.println("SYNACK Packet sent to " + this.address + ":" + port);

        changeState(SYN_RCVD);
        break;

      // Client has sent a SYN to the server and awaits a SYN+ACK response
      // Upon receiving a SYN+ACK, reply with ACK and switch to ESTABLISHED state
      case SYN_SENT:

        // Check if packet is a SYN+ACK
        if (!(p.synFlag && p.ackFlag)) {
          System.err.println("DEBUG: Packet received during state SYN_SENT but it was not a SYN+ACK packet.");
          break;
        }
        this.seqNum = p.ackNum;
        packetLength = p.data != null? p.data.length : 20;
        this.ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;

        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1000, new byte[0]);
        // System.out.println("DEBUG: TCPPacket created.");

        TCPWrapper.send(ackPacket, this.address);
        // System.out.println("DEBUG: packet sent.");

        System.out.println("ACK Packet sent to " + this.address + ":" + port);

        changeState(ESTABLISHED);
        break;
      
      // Server has sent SYN+ACK and now awaits final ACK
      // Upon receiving an ACK, switch to ESTABLISHED state
      case SYN_RCVD:

        // Check if packet is an ACK
        if (!p.ackFlag) {
          System.err.println("DEBUG: Packet received during state SYN_RCVD but it was not a ACK packet.");
          break;
        }

        changeState(ESTABLISHED);
        break;

      // Server has established connection and is awaiting data
      // Server receives data packets and parses them 
      // Or if packet is a FIN, reply with ACK and begin to close
      case ESTABLISHED:

        // Check for FIN
        if (p.finFlag) {
          packetLength = p.data != null? p.data.length : 20;
          ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;
          TCPPacket ack = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1000, new byte[0]);
          TCPWrapper.send(ack, address);
          changeState(CLOSE_WAIT);
          break;
        }

        else if (p.data != null && p.data.length > 0) {
          try {
            // Push data to Application
            appOS.write(p.data);
            appOS.flush();
          } catch (IOException e) {
            e.printStackTrace();
          }
        
          // Create and send ACK
          packetLength = p.data != null? p.data.length : 20;
          ackNum = (p.seqNum + packetLength) % TCPPacket.MAX_PACKET_SIZE;
          TCPPacket ack = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1000, new byte[0]);
          TCPWrapper.send(ack, address);
        }
        // stay on ESTABLISHED state
        break;
      
      // Remote host has closed and received an ACK, local host has sent its FIN and awaits an ACK
      // Local host receives and ACK and switches to TIME_WAIT state
      case LAST_ACK:
        // Check if it is an ACK
        if(p.ackFlag) {
          changeState(TIME_WAIT);
          break;
        }
        System.err.println("DEBUG: Packet received during state LAST_ACK but it was not a ACK packet.");
        break;

      // Local host has sent a FIN to the remote host and is awaiting a FIN
      // If the local host receives a FIN, send ACK and switch to CLOSING state
      // If the local host receives an ACK, switch to FIN_WAIT_2 state
      case FIN_WAIT_1:
        // Check if the packet is a FIN
        if(p.finFlag) {
          // This means the remote host has also called close() and now awaits an ACK
          // TODO - SEND ACK
          changeState(CLOSING);
          break;
        }

        // Check if the packet is an ACK
        else if(p.ackFlag) {
          // This means the remote host has received the FIN and is currently calling close()
          changeState(FIN_WAIT_2);
          break;
        }

        // Otherwise, ignore packet
        System.err.println("DEBUG: Packet received during state FIN_WAIT_1 but it was not a FIN or an ACK packet.");
        break;
      
      // Local host has sent a FIN and received an ACK and now waits for remote host to close and send a FIN
      // Local host receives a FIN and sends an ACK and switches to TIME_WAIT state
      case FIN_WAIT_2:
        // Check if the packet is a FIN
        if (p.finFlag) {
          ackNum = (p.seqNum + 20) % TCPPacket.MAX_PACKET_SIZE;
          TCPPacket ack = new TCPPacket(localport, port, seqNum,ackNum, true, false, false, 1000, new byte[0]);
          TCPWrapper.send(ack, this.address);
          changeState(TIME_WAIT);
        }

        // Otherwise ignore the packet
        System.err.println("DEBUG: Packet received during state FIN_WAIT_2 but it was not a FIN packet.");
        break;
    }
  }

  /**
   * Change the current state of the host and notify all threads
   * @param newState the state to switch to
   */
  private void changeState(String newState) {
    String previousState = this.current_state;
    this.current_state = newState;
    System.out.println("!!! " + previousState + "->" + newState);
    notifyAll();
  }

  private boolean checkOrder(int newSeq, int newAck) {
    // We assume that seqNum is updated? Add prevPackageSize
    if (this.ackNum == newSeq && this.seqNum == newAck){
      return true;
    }
    else {
      return false;
    }
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
      // System.out.println("DEBUG: current_state changed to " + LISTEN + " with localport " + localport);

      long timeStart = System.currentTimeMillis();
      long timeout = 10000;

      while (!current_state.equals(ESTABLISHED) && !current_state.equals(SYN_RCVD)) {
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
      // System.out.println("DEBUG: Connection established");
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
      TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, true, 1000, new byte[0]);
      TCPWrapper.send(finPacket, address);
      if (this.current_state.equals(ESTABLISHED)){
        changeState(FIN_WAIT_1);
      } else if (this.current_state.equals(CLOSE_WAIT)) {
        changeState(LAST_ACK);
      }
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
    String name = (String) ref;
    System.out.println("Timer reached timeout limit for: " + name);

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
  }
}
