package com.example.demo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Data
@Entity
@Table(name = "test_cases")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long dbId;

    private String id;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String precondition;

    @ElementCollection
    @CollectionTable(name = "test_case_steps", joinColumns = @JoinColumn(name = "test_case_db_id"))
    @Column(name = "step_description", columnDefinition = "TEXT")
    private List<String> steps;

    @Column(columnDefinition = "TEXT")
    private String expected_result;

    // Các mã yêu cầu mà test case này kiểm tra, lưu dạng "REQ-01,REQ-02".
    // Lưu chuỗi trong 1 cột (thay vì bảng phụ) để tránh giới hạn primary key của Aiven.
    @JsonIgnore
    @Column(name = "requirement_ids", columnDefinition = "TEXT")
    private String requirementIdsRaw;

    @JsonProperty("requirementIds")
    public List<String> getRequirementIds() {
        if (requirementIdsRaw == null || requirementIdsRaw.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(requirementIdsRaw.split(",")));
    }

    @JsonProperty("requirementIds")
    @JsonAlias({"requirement_ids", "requirementId"})
    public void setRequirementIds(List<String> ids) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (ids != null) {
            for (String item : ids) {
                if (item == null) continue;
                // AI đôi khi trả "REQ-01, REQ-02" trong một chuỗi -> tách ra
                for (String part : item.split("[,;\\s]+")) {
                    String code = part.trim().toUpperCase(Locale.ROOT);
                    if (!code.isEmpty()) clean.add(code);
                }
            }
        }
        this.requirementIdsRaw = clean.isEmpty() ? null : String.join(",", clean);
    }

    // Liên kết khóa ngoại (Foreign Key) ngược lại bảng ChatSession
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @JsonIgnore
    private ChatSession chatSession;
}