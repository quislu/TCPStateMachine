import java.io.*;
import java.net.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private static final int CLOSED = 0;
  private static final int SYN_SENT = 1;
  private static final int LISTEN = 2;
  private static final int SYN_RCVD = 3;
  private static final int ESTABLISHED = 4;
  private static final int CLOSE_WAIT = 5;
  private static final int LAST_ACK = 6;
  private static final int FIN_WAIT_1 = 7;
  private static final int FIN_WAIT_2 = 8;
  private static final int CLOSING = 9;
  private static final int TIME_WAIT = 10;

  private Demultiplexer D;
  private Timer tcpTimer;
  private int current_state;

  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;
    this.current_state = CLOSED;    
    System.out.println("DEBUG: Student Socket initialized.");
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException{
    localport = D.getNextAvailablePort();
    this.address = address;
    this.port = port;
    int seqNum = 10;
    System.out.println("DEBUG: Variables initialized.");

    D.registerConnection(address, port, localport, this);
    System.out.println("DEBUG: Connection registered.");

    TCPPacket synPacket = new TCPPacket(localport, port, seqNum, 0, false, true, false, 1000, new byte[0]);
    System.out.println("DEBUG: TCPPacket created.");

    TCPWrapper.send(synPacket, address);
    current_state = SYN_SENT;
    System.out.println("DEBUG: packet sent.");

    System.out.println("SYN Packet sent to " + address + ":" + port);
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
    System.out.println("Packet received: " + p);

    //TODO - create switch statements based on state
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
    D.registerListeningSocket(localport, this);
    current_state = LISTEN; 
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
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
    
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
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
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
