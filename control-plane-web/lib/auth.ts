export const TOKEN_COOKIE = "fh_token";

export function getToken(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const match = document.cookie
    .split("; ")
    .find((entry) => entry.startsWith(`${TOKEN_COOKIE}=`));
  return match ? decodeURIComponent(match.slice(TOKEN_COOKIE.length + 1)) : null;
}

export function setToken(token: string, expiresAt: string): void {
  const expires = new Date(expiresAt);
  const maxAge = Math.max(0, Math.floor(expires.getTime() / 1000) - Math.floor(Date.now() / 1000));
  document.cookie = `${TOKEN_COOKIE}=${encodeURIComponent(token)}; path=/; max-age=${maxAge}; samesite=lax`;
}

export function clearToken(): void {
  document.cookie = `${TOKEN_COOKIE}=; path=/; max-age=0; samesite=lax`;
}
