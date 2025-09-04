package mz.org.csaude.sespcet.api.api.response;

import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import mz.org.csaude.sespcet.api.api.RestAPIResponse;

import java.util.ArrayList;
import java.util.List;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class PaginatedResponse<T> implements RestAPIResponse {

    private int status;
    private String message;
    private long total;
    private int page;
    private int size;
    private Sort.Order order;

    @Builder.Default // <- Garante que mesmo se o builder não receber "content", vai inicializar com lista vazia
    private List<T> content = new ArrayList<>();

    public static <T> PaginatedResponse<T> of(List<T> content, long total, Pageable pageable, String message) {
        return PaginatedResponse.<T>builder()
                .status(200)
                .message(message)
                .content(content != null ? content : new ArrayList<>()) // Protege contra null explícito
                .total(total)
                .page(pageable.getNumber())
                .size(pageable.getSize())
                .order(pageable.getSort().getOrderBy().isEmpty() ? null : pageable.getSort().getOrderBy().get(0))
                .build();
    }
}
