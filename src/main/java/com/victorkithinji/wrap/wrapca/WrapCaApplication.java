package com.victorkithinji.wrap.wrapca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WrapCaApplication {

	public static void main(String[] args) {
		SpringApplication.run(WrapCaApplication.class, args);
	}

}
