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

    /**
     * SEC-26: tenant-scoped by-id price finder. Empty result means "absent OR not owned by this
     * tenant", so callers can collapse both into a single not-found path (no cross-tenant existence
     * oracle). Use this — not {@link #findPriceById} — whenever the price id is client-supplied, so a
     * tenant-A caller cannot bind their subscription to a tenant-B Price.
     */
    Optional<Price> findPriceByIdAndTenantId(String id, String tenantId);

    List<Price> findPricesByProduct(String productId);

    List<Price> findPricesByTenant(String tenantId, int limit, int offset);
}
