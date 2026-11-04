export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const MIN_PASSWORD = 6;

export function networkMessage(message: string | undefined, fallback: string): string {
  if (!message) return fallback;
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  if (/rate limit|too many/i.test(message)) return "Too many tries. Wait a minute and try again.";
  return message;
}
