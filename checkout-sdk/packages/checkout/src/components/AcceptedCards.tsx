/**
 * AcceptedCards — a muted row of accepted card-network marks shown above the
 * card-details group (spec Trust signal #2). The marks are rendered at 36x24
 * and dimmed (opacity .4 via .checkout__accepted-cards svg) so they read as a
 * quiet reassurance, not a loud badge wall. On detection, the full-color single
 * mark crossfades into the field right edge (handled inside the PCI iframe).
 *
 * These are the same authentic flat marks used in the iframe brand chip
 * (Visa / Mastercard / Amex / Discover), inlined here so the SPA stays
 * self-contained. Official brand geometry + colors; no <text>, no recolor.
 */

const VISA = (
  <svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Visa">
    <rect width="32" height="20" rx="2" fill="#1A1F71" />
    <path d="M13.2 13.5L14.7 6.5H16.8L15.3 13.5H13.2Z" fill="white" />
    <path d="M21.8 6.7C21.4 6.5 20.7 6.3 19.9 6.3C17.8 6.3 16.3 7.4 16.3 9C16.3 10.2 17.4 10.8 18.2 11.2C19 11.6 19.3 11.8 19.3 12.2C19.3 12.7 18.7 13 18.1 13C17.3 13 16.8 12.9 16.1 12.5L15.8 12.4L15.5 14.1C16 14.3 16.9 14.5 17.9 14.5C20.1 14.5 21.6 13.4 21.6 11.7C21.6 10.8 21 10.1 19.7 9.5C19 9.2 18.5 8.9 18.5 8.5C18.5 8.2 18.9 7.8 19.7 7.8C20.4 7.8 20.9 7.9 21.3 8.1L21.5 8.2L21.8 6.7Z" fill="white" />
    <path d="M24.2 6.5H22.6C22.1 6.5 21.7 6.6 21.5 7.2L18.5 13.5H20.7L21.1 12.3H23.8L24 13.5H26L24.2 6.5ZM21.8 10.7L22.7 8.3L23.2 10.7H21.8Z" fill="white" />
    <path d="M12.1 6.5L10 11.1L9.8 10L9.1 7.2C9 6.7 8.6 6.5 8.1 6.5H5.1L5 6.7C5.8 6.9 6.5 7.2 7.1 7.5L9 13.5H11.2L14.3 6.5H12.1Z" fill="white" />
  </svg>
);

const MASTERCARD = (
  <svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Mastercard">
    <rect width="32" height="20" rx="2" fill="#252525" />
    <circle cx="12" cy="10" r="6" fill="#EB001B" />
    <circle cx="20" cy="10" r="6" fill="#F79E1B" />
    <path d="M16 5.4C17.5 6.5 18.5 8.1 18.5 10C18.5 11.9 17.5 13.5 16 14.6C14.5 13.5 13.5 11.9 13.5 10C13.5 8.1 14.5 6.5 16 5.4Z" fill="#FF5F00" />
  </svg>
);

const AMEX = (
  <svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="American Express">
    <rect width="32" height="20" rx="2" fill="#2E77BC" />
    <path d="M5 13.5V6.5H8.5L9.3 8.5L10.1 6.5H13.5V13.5H11.5V8.5L10.5 11H8L7 8.5V13.5H5Z" fill="white" />
    <path d="M14 13.5V6.5H19.5L20 7.8H16V9.3H19.3V10.5H16V12.2H20L19.5 13.5H14Z" fill="white" />
    <path d="M20.5 13.5L23.5 9.8L20.5 6.5H23L24.5 8.5L26 6.5H28.5L25.5 9.8L28.5 13.5H26L24.5 11.2L23 13.5H20.5Z" fill="white" />
  </svg>
);

const DISCOVER = (
  <svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Discover">
    <rect x="0.5" y="0.5" width="31" height="19" rx="2" fill="#fff" stroke="#E5E7EB" />
    <path d="M31.5 14.5C25 18.5 14 19.2 7 19.5H30C30.83 19.5 31.5 18.83 31.5 18V14.5Z" fill="#F47216" />
    <circle cx="20.4" cy="10" r="2.9" fill="#F47216" />
    <g fill="#1D1D1D">
      <rect x="3.2" y="7.4" width="0.95" height="5.2" rx="0.2" />
      <path d="M4.9 7.4H6.1C7.55 7.4 8.55 8.5 8.55 10C8.55 11.5 7.55 12.6 6.1 12.6H4.9V7.4ZM5.85 8.25V11.75H6.05C6.95 11.75 7.55 11.05 7.55 10C7.55 8.96 6.95 8.25 6.05 8.25H5.85Z" />
      <path d="M10.6 9.4C10.05 9.2 9.9 9.07 9.9 8.82C9.9 8.54 10.18 8.32 10.55 8.32C10.81 8.32 11.02 8.43 11.25 8.69L11.74 8.04C11.34 7.69 10.86 7.52 10.34 7.52C9.5 7.52 8.86 8.1 8.86 8.88C8.86 9.53 9.16 9.87 10.02 10.18C10.38 10.31 10.57 10.39 10.66 10.45C10.85 10.57 10.94 10.74 10.94 10.94C10.94 11.32 10.64 11.6 10.22 11.6C9.78 11.6 9.42 11.38 9.21 10.97L8.6 11.56C9.02 12.18 9.53 12.45 10.25 12.45C11.21 12.45 11.9 11.81 11.9 10.89C11.9 10.13 11.58 9.79 10.6 9.4Z" />
      <path d="M12.2 10C12.2 11.53 13.4 12.7 14.95 12.7C15.39 12.7 15.76 12.61 16.22 12.4V11.27C15.81 11.69 15.45 11.85 14.99 11.85C13.96 11.85 13.23 11.1 13.23 10C13.23 8.96 13.98 8.16 14.95 8.16C15.43 8.16 15.8 8.33 16.22 8.76V7.63C15.78 7.4 15.41 7.32 14.97 7.32C13.44 7.32 12.2 8.51 12.2 10Z" />
    </g>
  </svg>
);

export function AcceptedCards() {
  return (
    <div className="checkout__accepted-cards" aria-label="Accepted cards">
      {VISA}
      {MASTERCARD}
      {AMEX}
      {DISCOVER}
    </div>
  );
}
