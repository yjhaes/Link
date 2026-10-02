package com.example.shortlink;
import com.example.shortlink.stats.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class VisitPublisherBrokerTest {
 @Test void realBrokerDistinguishesRoutedReturnAndMissingExchangeThenAcceptsNewEvent() {
  var factory=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("RABBIT_TEST_PORT","15672")));
  factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);factory.setPublisherReturns(true);
  factory.setChannelCacheSize(16);factory.setChannelCheckoutTimeout(200);factory.setCloseTimeout(500);
  var template=new RabbitTemplate(factory);template.setMandatory(true);
  var admin=new RabbitAdmin(factory);String queue="visit-test-03-"+java.util.UUID.randomUUID();
  var recorder=new AsyncVisitRecorder(new VisitRabbitProperties(null,null,null,null,null,null,null,null,null,null,null,null,false),template,mock(RabbitAdmin.class),mock(SimpleMessageListenerContainer.class),new VisitMessageCodec(),factory);
  try {
   admin.declareExchange(new DirectExchange(VisitRabbitConfiguration.EXCHANGE,true,false));
   admin.declareQueue(new Queue(queue,false,false,false));
   var binding=new Binding(queue,Binding.DestinationType.QUEUE,VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,null);admin.declareBinding(binding);
   recorder.ready();recorder.record(VisitPublisherFailureTest.event());
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("accepted")).isEqualTo(1));
   assertThat(template.receive(queue,2000)).isNotNull();
   admin.removeBinding(binding);
   // Use a separate exchange-specific template to avoid other agents' legitimate bindings.
   String absentRoute="route-03-"+java.util.UUID.randomUUID();
   var cd=new CorrelationData();template.send(VisitRabbitConfiguration.EXCHANGE,absentRoute,new Message(new byte[]{1}),cd);
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->cd.getFuture().isDone());assertThat(cd.getReturned()).isNotNull();assertThat(cd.getFuture().join().isAck()).isTrue();
   var missing=new CorrelationData();try {template.send("missing-03-"+java.util.UUID.randomUUID(),"x",new Message(new byte[]{1}),missing);}catch(Exception expected){}
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->missing.getFuture().isDone());assertThat(missing.getFuture().join().isAck()).isFalse();
   factory.resetConnection();admin.declareBinding(binding);recorder.record(VisitPublisherFailureTest.event());
   org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("accepted")).isEqualTo(2));
   assertThat(template.receive(queue,2000)).isNotNull();
  }finally {admin.deleteQueue(queue);recorder.close();}
 }
 @Test void realTcpBlackoutExpiresConfirmAndRecoveryAcceptsOnlyNewEvents() throws Exception {
  try(var proxy=new TcpBlackout(Integer.parseInt(System.getenv().getOrDefault("RABBIT_TEST_PORT","15672")))) {
   var factory=new CachingConnectionFactory("127.0.0.1",proxy.port());factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);factory.setPublisherReturns(true);factory.setCloseTimeout(500);
   var template=new RabbitTemplate(factory);template.setMandatory(true);var admin=new RabbitAdmin(factory);
   var consumerFactory=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("RABBIT_TEST_PORT","15672")));var consumerConnection=consumerFactory.createConnection();
   String queue="visit-tcp-03-"+java.util.UUID.randomUUID();
   var recorder=new AsyncVisitRecorder(new VisitRabbitProperties(null,null,null,null,null,null,null,null,null,null,null,null,false),template,mock(RabbitAdmin.class),mock(SimpleMessageListenerContainer.class),new VisitMessageCodec(),factory);
   try {
    admin.declareExchange(new DirectExchange(VisitRabbitConfiguration.EXCHANGE,true,false));admin.declareQueue(new Queue(queue,false,false,false));
    admin.declareBinding(new Binding(queue,Binding.DestinationType.QUEUE,VisitRabbitConfiguration.EXCHANGE,VisitRabbitConfiguration.KEY,null));
    recorder.ready();recorder.record(VisitPublisherFailureTest.event());
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("accepted")).isEqualTo(1));
    assertThat(template.receive(queue,2000)).isNotNull();
    for(int cycle=1;cycle<=2;cycle++){final int round=cycle;
    proxy.blackout=true;recorder.record(VisitPublisherFailureTest.event());
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("unknown")).isEqualTo(round));
    assertThat(recorder.snapshot().unconfirmed()).isZero();assertThat(recorder.snapshot().outcomes().get("recovery")).isEqualTo(round);
    proxy.blackout=false;
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->!recorder.snapshot().recovering());
    recorder.record(VisitPublisherFailureTest.event());
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(recorder.snapshot().outcomes().get("accepted")).isEqualTo(round+1));
    assertThat(template.receive(queue,2000)).isNotNull();assertThat(template.receive(queue,200)).isNull();
    assertThat(consumerFactory.createConnection()).isSameAs(consumerConnection);assertThat(consumerConnection.isOpen()).isTrue();
    }
   }finally {proxy.blackout=false;admin.deleteQueue(queue);recorder.close();consumerFactory.destroy();}
  }
 }
 /** Drops TCP bytes after a successful handshake; this proves missing confirms, not blocked-write cancellation. */
 static final class TcpBlackout implements AutoCloseable {
  final java.net.ServerSocket server=new java.net.ServerSocket(0);final int brokerPort;
  final java.util.List<java.net.Socket> sockets=new java.util.concurrent.CopyOnWriteArrayList<>();volatile boolean blackout, unavailable;
  TcpBlackout(int port) throws java.io.IOException {brokerPort=port;daemon(()->{try {while(!server.isClosed()){var client=server.accept();if(unavailable){client.close();continue;}var upstream=new java.net.Socket("127.0.0.1",brokerPort);sockets.add(client);sockets.add(upstream);relay(client,upstream,true);relay(upstream,client,false);}}catch(java.io.IOException ignored){}});}
  int port(){return server.getLocalPort();}
  void relay(java.net.Socket source,java.net.Socket target,boolean outbound){daemon(()->{try {var bytes=new byte[8192];int n;while((n=source.getInputStream().read(bytes))!=-1){if(!(outbound&&blackout)){target.getOutputStream().write(bytes,0,n);target.getOutputStream().flush();}}}catch(java.io.IOException ignored){}finally{try{target.close();}catch(java.io.IOException ignored){}}});}
  static void daemon(Runnable work){var t=new Thread(work,"visit-test-tcp");t.setDaemon(true);t.start();}
  public void close() throws java.io.IOException {server.close();for(var socket:sockets)socket.close();}
 }}





