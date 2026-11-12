package com.example.line1thon.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DuplicateResolveRequest {
    private Long groupId;
    private String resolution; // "MERGE" 또는 "SEPARATE"
}