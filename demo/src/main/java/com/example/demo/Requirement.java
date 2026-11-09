package com.example.demo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Một yêu cầu (requirement) được tách ra từ User Story.
 * Test case liên kết tới yêu cầu thông qua mã (code), ví dụ "REQ-01".
 */
@Getter
@Setter
@Entity
@Table(name = "requirements")
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long dbId;

    // Mã yêu cầu, duy nhất trong một phiên (VD: REQ-01)
    @Column(length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    // High | Medium | Low
    @Column(length = 20)
    private String priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @JsonIgnore
    private ChatSession chatSession;
}
