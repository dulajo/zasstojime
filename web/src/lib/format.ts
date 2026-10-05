const number = new Intl.NumberFormat("cs-CZ");
const percent = new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 1, minimumFractionDigits: 1 });
const wholePercent = new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 1, minimumFractionDigits: 1 });

export function formatNumber(value: number): string {
  return number.format(value);
}

export function formatPercent(value: number | null, digits: 0 | 1 = 1): string {
  return value == null ? "—" : `${(digits === 0 ? wholePercent : percent).format(value)} %`;
}

/** Krátké doby s desetinou minuty, aby průměr na spoj nesplýval na „2 min“. */
export function formatMinutes(seconds: number | null): string {
  return seconds == null ? "—" : `${decimal.format(seconds / 60)} min`;
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
  return date === addDays(today, -1) && Number(pragueParts(now).hour) < 6;
}

export function formatClock(value: string): string {
  return new Intl.DateTimeFormat("cs-CZ", { timeZone: "Europe/Prague", hour: "2-digit", minute: "2-digit" })
    .format(new Date(value));
}

export function formatWeekdayDate(date: string): string {
  return new Intl.DateTimeFormat("cs-CZ", { weekday: "long", day: "numeric", month: "long" })
    .format(new Date(`${date}T12:00:00Z`));
}

/** Celé minuty zpoždění, useknuté jako na odjezdových tabulích: 179 s je ještě „+2 min“ a včas. */
export function delayMinutes(seconds: number): number {
  return Math.trunc(seconds / 60);
}

/** Zpoždění jednoho spoje v celých minutách, jak ho ukazují odjezdové tabule. */
export function formatDelay(seconds: number | null | undefined): string {
  if (seconds == null) return "—";
  const minutes = delayMinutes(seconds);
  if (minutes === 0) return "0 min";
  return `${minutes > 0 ? "+" : "−"}${Math.abs(minutes)} min`;
}

export type DelayBand = "early" | "ontime" | "slight" | "major" | "critical";

/** Pásma odjezdu podle metodiky (Měření odjezdů). */
export function delayBand(seconds: number): DelayBand {
  if (seconds <= -60) return "early";
  if (seconds < 180) return "ontime";
  if (seconds < 300) return "slight";
  if (seconds < 900) return "major";
  return "critical";
}

export const delayBandNames: Record<DelayBand, string> = {
  early: "Předjetí", ontime: "Včas", slight: "Mírné zpoždění", major: "Výrazné zpoždění", critical: "Kritické zpoždění",
};

export function addDays(date: string, days: number): string {
  const value = new Date(`${date}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/** Aktuální čas v Praze jako HH:MM, pro výchozí výběr spoje. */
export function nowInPrague(now = new Date()): string {
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Prague", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .format(now);
}
