/**
 * Inbound REST layer.
 *
 * <p>Keep resources thin: map request to scan request, call {@code analysis}, map results to
 * DTOs. If a rule or a prompt ever appears in this package, it is in the wrong place.
 */
package io.kubepilot.server;
