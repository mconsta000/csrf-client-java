package com.skunkworks.clientjava;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@SpringBootApplication
public class ClientJavaApplication {
    private static final Logger log = LoggerFactory.getLogger(ClientJavaApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ClientJavaApplication.class, args);
    }

    @Bean
    public CookieJar jar() {
        return new CookieJar() {
            private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

            @Override
            public void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> cookies) {
                cookieStore.put(url.host(), cookies);
            }

            @Override
            public List<Cookie> loadForRequest(@NonNull HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new ArrayList<Cookie>();
            }
        };
    }

    @Bean
    public OkHttpClient client(CookieJar jar) {
        return new OkHttpClient.Builder()
                .cookieJar(jar)
                .authenticator(new Authenticator() {
                    @Override
                    public Request authenticate(Route route, Response response) throws IOException {
                        if (response.request().header("Authorization") != null) {
                            return null; // Give up, we've already attempted to authenticate.
                        }

                        log.debug("Authenticating for response: " + response);
                        log.debug("Challenges: " + response.challenges());
                        String credential = Credentials.basic("user", "password");
                        return response.request().newBuilder()
                                .header("Authorization", credential)
                                .build();
                    }
                })
                .build();
    }

    @Bean
    public CommandLineRunner run(OkHttpClient client, CookieJar jar) throws Exception {
        return args -> {
            Request getRequest = new Request.Builder()
                    .get()
                    .url("http://localhost:8080/api/resource")
                    .build();

            try (Response getResponse = client.newCall(getRequest).execute()) {
                if (!getResponse.isSuccessful()) throw new IOException("Unexpected code from GET " + getResponse);

                log.debug("GET {}", getResponse.body().string());

                jar.loadForRequest(getRequest.url()).stream()
                        .filter(c -> c.name().equals("XSRF-TOKEN"))
                        .findFirst()
                        .ifPresent(c -> {
                            try {
                                Request postRequest = new Request.Builder()
                                        .post(RequestBody.create("{}", MediaType.parse("application/json; charset=utf-8")))
                                        .url("http://localhost:8080/api/resource")
                                        .header("X-XSRF-TOKEN", c.value())
                                        .build();

                                try (Response postResponse = client.newCall(postRequest).execute()) {
                                    if (!postResponse.isSuccessful())
                                        throw new IOException("Unexpected code from POST " + postResponse);

                                    log.debug("POST {}", postResponse.body().string());
                                }
                            } catch (IOException e) {
                                log.error("Post failed", e);
                            }
                        });
            }
        };
    }
}
