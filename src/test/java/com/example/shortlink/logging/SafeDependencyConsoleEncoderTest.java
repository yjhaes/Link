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
   var business=context.getLogger("com.example.shortlink.stats.VisitRabbitConfiguration");business.addAppender(appender);business.warn("Visit listener failed: category=consumption");
   String logs=output.toString(java.nio.charset.StandardCharsets.UTF_8);
   assertThat(logs).contains("WARN com.zaxxer.hikari.pool.ProxyConnection Dependency event: category=database", "ERROR org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer Dependency event: category=mq", "WARN com.rabbitmq.client.impl.ForgivingExceptionHandler Dependency event: category=mq", "Visit listener failed: category=consumption");
   assertThat(logs.lines().count()).isEqualTo(4);assertThat(logs).doesNotContain("payload-canary","hash-canary","password-canary","SQLException","RuntimeException","IllegalArgumentException");
  } finally {context.stop();}
 }
}
