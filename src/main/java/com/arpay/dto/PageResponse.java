package com.arpay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> content;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean last;

    // Aliases for common pagination field names
    public int getPage() {
        return pageNumber;
    }

    public int getSize() {
        return pageSize;
    }

    public boolean isFirst() {
        return pageNumber == 0;
    }
}
