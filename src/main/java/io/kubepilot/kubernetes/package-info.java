/**
 * Cluster access: the fabric8 client, API reference, and snapshot assembly.
 *
 * <p>This is the seam that later swaps direct API calls for informer-backed cache reads with
 * nothing above it changing, so keep the read interface narrow and snapshot-shaped.
 *
 * <p>Together with {@code custom}, the only package allowed to import {@code io.fabric8}.
 */
package io.kubepilot.kubernetes;
