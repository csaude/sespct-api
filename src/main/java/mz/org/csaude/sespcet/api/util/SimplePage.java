package mz.org.csaude.sespcet.api.util;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;

import java.util.Collections;
import java.util.List;

public class SimplePage<T> implements Page<T> {

    private final List<T> content;
    private final Pageable pageable;
    private final long total;

    public SimplePage(List<T> content, Pageable pageable, long total) {
        this.content = content != null ? content : Collections.emptyList();
        this.pageable = pageable;
        this.total = total;
    }

    @Override
    public List<T> getContent() {
        return content;
    }

    @Override
    public int getSize() {
        return pageable.getSize();
    }

    @Override
    public long getTotalSize() {
        return total;
    }

    @Override
    public boolean hasTotalSize() {
        return total > 0;
    }

    @Override
    public boolean hasNext() {
        return getOffset() + getSize() < total;
    }

    @Override
    public int getTotalPages() {
        return (int) Math.ceil((double) total / (double) getSize());
    }

    @Override
    public Sort getSort() {
        return pageable.getSort();
    }

    @Override
    public long getOffset() {
        return pageable.getOffset();
    }

    @Override
    public Pageable getPageable() {
        return pageable;
    }
}
