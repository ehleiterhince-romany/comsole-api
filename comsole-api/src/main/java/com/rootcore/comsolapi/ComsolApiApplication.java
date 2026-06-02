package com.rootcore.comsolapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.solr.SolrAutoConfiguration;

@SpringBootApplication(exclude = {SolrAutoConfiguration.class})
public class ComsolApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComsolApiApplication.class, args);
    }

}
