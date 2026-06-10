/**
 * Shared kernel — value objects (Money, PrefixedId, SessionToken), exceptions,
 * event envelope/serialization, and topic constants used by every module.
 *
 * <p>Declared {@code OPEN} because the entire module is intentionally public
 * API: its subpackages ({@code id}, {@code exception}, {@code event}, …) are
 * consumed across all modules, which Modulith would otherwise flag as
 * internal-type access.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package io.nexuspay.common;
