const number = new Intl.NumberFormat("cs-CZ");
const percent = new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 1, minimumFractionDigits: 1 });

export function formatNumber(value: number): string {
  return number.format(value);
}

export function formatPercent(value: number | null): string {
  return value == null ? "—" : `${percent.format(value)} %`;
}

export function formatDuration(seconds: number | null): string {
  if (seconds == null) return "—";
  const minutes = Math.round(Math.abs(seconds) / 60);
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  const sign = seconds < 0 ? "−" : "";
  return hours > 0 ? `${sign}${hours} h ${remainder} min` : `${sign}${minutes} min`;
}

export function formatDate(date: string): string {
  return new Intl.DateTimeFormat("cs-CZ", { day: "numeric", month: "long", year: "numeric" })
    .format(new Date(`${date}T12:00:00Z`));
}

export function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat("cs-CZ", {
    timeZone: "Europe/Prague", day: "numeric", month: "numeric", hour: "2-digit", minute: "2-digit",
  }).format(new Date(value));
}

function pragueParts(now: Date): Record<string, string> {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Prague", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", hourCycle: "h23",
  }).formatToParts(now);
  return Object.fromEntries(parts.map(({ type, value }) => [type, value]));
}

export function todayInPrague(now = new Date()): string {
  const values = pragueParts(now);
  return `${values.year}-${values.month}-${values.day}`;
}

/** Dnešek a do 06:00 i včerejšek, jehož provozní den se ještě uzavírá (ADR-0050). */
export function isLiveDay(date: string, now = new Date()): boolean {
  const today = todayInPrague(now);
  if (date === today) return true;
  const yesterday = new Date(`${today}T12:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  return date === yesterday.toISOString().slice(0, 10) && Number(pragueParts(now).hour) < 6;
}
