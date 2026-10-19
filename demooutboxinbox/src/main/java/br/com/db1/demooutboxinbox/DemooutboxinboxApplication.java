package br.com.db1.demooutboxinbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
		scanBasePackages = {
				"br.com.db1.demooutboxinbox",
				"br.com.db1.poc.outbox"
		}
)
public class DemooutboxinboxApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemooutboxinboxApplication.class, args);
	}

}
