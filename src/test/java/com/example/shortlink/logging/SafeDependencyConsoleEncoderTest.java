package com.example.shortlink.logging;



import ch.qos.logback.classic.*;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
class SafeDependencyConsoleEncoderTest {
 @Test void jdbcAndMqCanariesAreReplacedWhileEachSeverityAndSafeBusinessEventSurvives() {
  var context=new LoggerContext();context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());context.start();
  try {
   var output=new ByteArrayOutputStream();
   var encoder=new SafeDependencyConsoleEncoder();encoder.setContext(context);encoder.setPattern("%date %level %logger %msg%n%ex");encoder.start();
   var appender=new OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.setContext(context);appender.setEncoder(encoder);appender.setOutputStream(output);appender.start();
   String canary="payload-canary hash-canary password-canary";
   var jdbc=context.getLogger("com.zaxxer.hikari.pool.ProxyConnection");jdbc.addAppender(appender);
   jdbc.warn("JDBC broken {}",canary,new SQLException(canary,"08S01"));
   var mq=context.getLogger("org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer");mq.addAppender(appender);mq.error("Conversion {}",canary,new IllegalArgumentException(canary));
   var nativeMq=context.getLogger("com.rabbitmq.client.impl.ForgivingExceptionHandler");nativeMq.addAppender(appender);nativeMq.warn("Native shutdown {}",canary,new com.rabbitmq.client.ShutdownSignalException(true,false,null,canary));
   var business=context.getLogger("com.example.shortlink.stats.messaging.VisitRabbitConfiguration");business.addAppender(appender);business.warn("Visit listener failed: category=consumption");
   String logs=output.toString(java.nio.charset.StandardCharsets.UTF_8);
   assertThat(logs).contains("WARN com.zaxxer.hikari.pool.ProxyConnection Dependency event: category=database", "ERROR org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer Dependency event: category=mq", "WARN com.rabbitmq.client.impl.ForgivingExceptionHandler Dependency event: category=mq", "Visit listener failed: category=consumption");
   assertThat(logs.lines().count()).isEqualTo(4);assertThat(logs).doesNotContain("payload-canary","hash-canary","password-canary","SQLException","RuntimeException","IllegalArgumentException");
  } finally {context.stop();}
 }
 @Test void redisDriverBurstIsBoundedAndRawBusinessExceptionsCannotEscape() {
  var context=new LoggerContext();context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());context.start();
  try {
   var output=new ByteArrayOutputStream();
   var encoder=new SafeDependencyConsoleEncoder();encoder.setContext(context);encoder.setPattern("%level %logger %msg%n%ex");encoder.start();
   var appender=new OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.setContext(context);appender.setEncoder(encoder);appender.setOutputStream(output);appender.start();
   var redis=context.getLogger("io.lettuce.core.protocol.CommandHandler");redis.addAppender(appender);
   String canary="redis://password-canary original-url-canary query-canary cookie-canary visitor-hash-canary ip-canary ua-canary referer-canary payload-canary";
   for(int i=0;i<500;i++) redis.warn("Transport {}",canary,new IllegalStateException(canary));
   var http=context.getLogger("org.springframework.web.servlet.PageNotFound");http.addAppender(appender);
   for(int i=0;i<500;i++) http.warn("No mapping for METHOD_CANARY /unknown-path-canary {}",canary);
   var business=context.getLogger("com.example.shortlink.service.ShortLinkCreationService");business.addAppender(appender);
   business.error("Unexpected {}",canary,new IllegalArgumentException(canary));
   business.error("Coordination unconfirmed: operation=create category=committed-cache-unconfirmed shortCode=Ab12");
   String logs=output.toString(java.nio.charset.StandardCharsets.UTF_8);
   assertThat(logs).contains("category=redis", "category=http", "category=application", "shortCode=Ab12");
   assertThat(logs.lines().count()).isEqualTo(4);
   assertThat(logs).doesNotContain("canary","IllegalStateException","IllegalArgumentException");
  } finally {context.stop();}
 }
}
