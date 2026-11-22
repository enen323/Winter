package com.winter.reg;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.winter.reg.mapper")
public class WinterApplication {
  public static void main(String[] args) {
    SpringApplication.run(WinterApplication.class, args);
  }
}
