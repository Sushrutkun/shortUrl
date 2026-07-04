package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/** Published to Kafka with no partition key on every successful redirect. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent implements Serializable {
    private String shortCode;
    private Instant clickedAt;
}
