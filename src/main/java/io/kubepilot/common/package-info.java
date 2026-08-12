/**
 * Shared value types used by every other package: analysis results, failures, resource
 * references, severity. Mirrors k8sgpt's {@code pkg/common}.
 *
 * <p><b>Architecture rule:</b> this package depends on nothing but the JDK. No Quarkus, no
 * fabric8, no Jackson annotations. Prefer records and enums. Keeping it framework-free is
 * exactly what lets analyzers be unit-tested with no cluster and no LLM.
 */
package io.kubepilot.common;
