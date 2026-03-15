package io.nexuspay.billing.application.port.out;

import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.Product;

import java.util.List;
import java.util.Optional;

/**
 * Output port for product and price persistence.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public interface ProductRepository {

    Product saveProduct(Product product);

    Optional<Product> findProductById(String id);

    List<Product> findProductsByTenant(String tenantId, int limit, int offset);

    Price savePrice(Price price);

    Optional<Price> findPriceById(String id);

    List<Price> findPricesByProduct(String productId);

    List<Price> findPricesByTenant(String tenantId, int limit, int offset);
}
