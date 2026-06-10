/**
 * Double-entry ledger bounded context.
 *
 * <p>Declared {@code OPEN} (transitional): gateway and reconciliation consume
 * the use cases in {@code application} and types in {@code domain}, which
 * Modulith's default base-package-only exposure would flag as internal access.
 * The long-term fix is {@code @NamedInterface} declarations on those packages.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"common"}
)
package io.nexuspay.ledger;
