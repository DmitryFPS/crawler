package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TestController {
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/test/db")
    public String testDb() {
        jdbcTemplate.update("INSERT INTO pages(url, title, status) VALUES (?, ?, ?)",
                "https://test.local", "Test Title", "processed");
        return "OK";
    }
}
