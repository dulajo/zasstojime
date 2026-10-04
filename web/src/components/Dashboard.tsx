"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, type Day, type Leaderboard, type RecordDay, type RouteRank } from "@/lib/api";
import { demoData } from "@/lib/demo-data";
import { formatDate, formatDuration, formatMinutes, formatNumber, formatPercent, formatTimestamp, isLiveDay, todayInPrague } from "@/lib/format";

const categoryNames: Record<string, string> = {
  tram: "Tramvaje",
  bus_city: "Městské autobusy",
  bus_regional: "Regionální autobusy",
  train: "Vlaky",
  other: "Ostatní doprava",
};

function Metric({ label, value, note, accent = false }: {
  label: string; value: string; note: string; accent?: boolean;
}) {
  return <div className={`metric-card${accent ? " metric-card-accent" : ""}`}>
    <p className="eyebrow">{label}</p>
    <strong>{value}</strong>
    <span>{note}</span>
  </div>;
}

function Ranking({ title, subtitle, items, value }: {
  title: string; subtitle: string; items: RouteRank[]; value: (item: RouteRank) => string;
}) {
  return <section className="ranking-panel">
    <div className="panel-heading"><div><h3>{title}</h3><p>{subtitle}</p></div></div>
    {items.length === 0 ? <p className="muted">Pro tento den ještě není dost dat.</p> :
      <ol className="ranking-list">{items.slice(0, 5).map((item, index) =>
        <li key={`${item.route_id}-${index}`}>
          <span className="rank-index">{String(index + 1).padStart(2, "0")}</span>
          <span className="rank-line">{item.line}</span>
          <span className="rank-value">{value(item)}</span>
        </li>)}</ol>}
  </section>;
}

type Loaded<T> = { data?: T; failed: boolean };

const ordinals: Record<number, string> = { 2: "druhý", 3: "třetí", 4: "čtvrtý", 5: "pátý", 6: "šestý",
  7: "sedmý", 8: "osmý", 9: "devátý", 10: "desátý" };

/** Lidská věta k podílu zpožděných spojů. Přesná čísla stojí vedle, věta je jen přibližná. */
function liveSentence(late: number, running: number): string {
  if (late === 0) return "Právě teď nemá zpoždění žádný spoj na trase.";
  const share = late / running;
  if (share >= 0.5) return "Právě teď má zpoždění víc než polovina spojů na trase.";
  const ordinal = ordinals[Math.round(1 / share)];
  return ordinal ? `Zhruba každý ${ordinal} spoj na trase má právě teď zpoždění.` :
    "Spoje na trase, které mají právě teď zpoždění.";
}

function Headline({ day, isToday }: { day: Day; isToday: boolean }) {
  const { running_trips: running, running_late_trips: late } = day;
  const network = day.network;
  const live = running != null && late != null && running > 0;
  return <section aria-label="Hlavní čísla" className={`headline${live ? "" : " headline-single"}`}>
    {live && <div className="headline-live">
      <span className="headline-kicker"><span className="live-dot" /> Právě teď</span>
      <strong className="headline-number">{formatPercent((late / running) * 100, 0)}</strong>
      <p className="headline-sentence">{liveSentence(late, running)}</p>
      <span className="headline-detail"><b>{formatNumber(late)}</b> z {formatNumber(running)} spojů na trase jede 3 min a víc pozdě</span>
    </div>}
    <div className="headline-index">
      <span className="headline-kicker">Index dochvilnosti {isToday ? "dnes" : "za den"}</span>
      <strong className="headline-number">{formatPercent(network.punctuality_pct)}</strong>
      <p className="headline-sentence">odjezdů ze zastávek bylo včas</p>
      <span className="headline-detail">{formatNumber(network.dep_on_time)} z {formatNumber(network.dep_total)} odjezdů</span>
    </div>
  </section>;
}

function Overview({ day, isToday, leaderboard, record }: {
  day: Day; isToday: boolean; leaderboard: Loaded<Leaderboard>; record: Loaded<RecordDay>;
}) {
  const categories = Object.entries(day.categories);
  const network = day.network;
  const perTrip = network.trips_total > 0 ? network.sum_max_delay / network.trips_total : null;
  return <>
    {day.incomplete && <div className="notice">Sběr měl {isToday ? "dnes" : "v tento den"} delší výpadek. Uvedená čísla jsou neúplná.</div>}
    <Headline day={day} isToday={isToday} />
    <section aria-labelledby="overview-title" className="section-block section-block-tight">
      <div className="section-heading"><div><span className="section-kicker">Přehled dne</span><h2 id="overview-title">Kolik zpoždění se nasbíralo</h2></div>
        <p>Dochvilnost počítáme při odjezdu z každé zastávky, ne až na konečné.</p></div>
      <div className="metric-grid metric-grid-3">
        <Metric label="Součet zpoždění spojů" value={formatDuration(network.sum_max_delay)} note={`Největší zpoždění každého z ${formatNumber(network.trips_total)} spojů, sečtené dohromady`} accent />
        <Metric label="Průměr na jeden spoj" value={formatMinutes(perTrip)} note="Největší zpoždění spoje v průměru, včetně spojů bez zpoždění" />
        <Metric label="Průměr zpožděného odjezdu" value={formatDuration(network.average_late_delay_s)} note={`Jen ${formatNumber(network.dep_late)} odjezdů se zpožděním 3 min a víc`} />
      </div>
    </section>

    <section aria-labelledby="categories-title" className="section-block">
      <div className="section-heading"><div><span className="section-kicker">Podle dopravy</span><h2 id="categories-title">Každý druh zvlášť</h2></div>
        <p>Metro zatím nezobrazujeme; jeho zpoždění čekají na ověření.</p></div>
      {categories.length === 0 ? <div className="empty-panel">Rozpad podle druhů dopravy zatím nemáme.</div> :
        <div className="category-grid">{categories.map(([key, totals]) =>
          <article className="category-card" key={key}>
            <div className="category-top"><h3>{categoryNames[key] ?? key}</h3><span>{formatPercent(totals.punctuality_pct)}</span></div>
            <div className="progress-track"><div className="progress-fill" style={{ width: `${Math.min(100, Math.max(0, totals.punctuality_pct ?? 0))}%` }} /></div>
            <p>{formatNumber(totals.dep_total)} změřených odjezdů</p>
          </article>)}</div>}
    </section>

    <section aria-labelledby="rankings-title" className="section-block">
      <div className="section-heading"><div><span className="section-kicker">Linky pod lupou</span><h2 id="rankings-title">Dnešní žebříčky</h2></div>
        <p>U průměrů a dochvilnosti zobrazujeme linky s alespoň 20 dokončenými spoji.</p></div>
      {leaderboard.data ? <div className="ranking-grid">
        <Ranking title="Největší součet" subtitle="Zpoždění spojů na lince" items={leaderboard.data.most_total_delay} value={(item) => formatDuration(item.totals.sum_max_delay)} />
        <Ranking title="Nejvíc na spoj" subtitle="Průměrné maximum spoje" items={leaderboard.data.highest_delay_per_trip} value={(item) => formatDuration(item.average_max_delay_s)} />
        <Ranking title="Nejhorší dochvilnost" subtitle="Podíl odjezdů včas" items={leaderboard.data.worst_punctuality} value={(item) => formatPercent(item.totals.punctuality_pct)} />
      </div> : <div className="empty-panel">{leaderboard.failed ? "Žebříčky se nepodařilo načíst." : "Načítáme žebříčky…"}</div>}
    </section>

    <section aria-labelledby="record-title" className="record-panel">
      <div><span className="section-kicker">Rekordman dne</span><h2 id="record-title">Největší zpoždění na trase</h2>
        <p>Bereme nejvyšší změřené zpoždění odjezdu, ne průběžný odhad polohy vozidla.</p></div>
      {record.data?.record ? <div className="record-result">
        <span>Linka {record.data.record.line}{record.data.record.headsign ? ` · ${record.data.record.headsign}` : ""}</span>
        <strong>+{formatDuration(record.data.record.delay_s)}</strong>
        {record.data.record.flagged && !record.data.record.verified && <em>Neověřeno</em>}
      </div> : <div className="record-result"><span>{record.data ? "Rekord pro tento den zatím nemáme." :
        record.failed ? "Rekord se nepodařilo načíst." : "Načítáme rekord…"}</span></div>}
    </section>
  </>;
}

const notFound = (error: unknown) => error instanceof ApiError && error.status === 404;
const retry = (failures: number, error: unknown) => !notFound(error) && failures < 3;

export function Dashboard() {
  const [date, setDate] = useState("");
  const [today, setToday] = useState("");
  const [demo, setDemo] = useState(false);
  const todayRef = useRef("");
  useEffect(() => {
    setDemo(new URLSearchParams(window.location.search).get("demo") === "1");
    // Po půlnoci posuneme výběr na nový den, pokud uživatel sledoval ten dosavadní.
    const tick = () => {
      const next = todayInPrague();
      const previous = todayRef.current;
      if (next === previous) return;
      todayRef.current = next;
      setToday(next);
      setDate((current) => !current || current === previous ? next : current);
    };
    tick();
    const timer = window.setInterval(tick, 60_000);
    return () => window.clearInterval(timer);
  }, []);
  const live = Boolean(date) && isLiveDay(date);
  // Dnešní data mohou nejprve vracet 404; další interval je musí načíst, až sběr začne.
  const refetchInterval = live ? 30_000 : false;
  const sample = useMemo(() => date && demo ? demoData(date) : null, [date, demo]);
  const dayQuery = useQuery({ queryKey: ["day", date], queryFn: ({ signal }) => api.day(date, signal),
    enabled: Boolean(date) && !demo, refetchInterval, retry });
  const leaderboardQuery = useQuery({ queryKey: ["leaderboard", date], queryFn: ({ signal }) => api.leaderboard(date, signal),
    enabled: Boolean(dayQuery.data) && !demo, refetchInterval, retry });
  const recordQuery = useQuery({ queryKey: ["record", date], queryFn: ({ signal }) => api.record(date, signal),
    enabled: Boolean(dayQuery.data) && !demo, refetchInterval, retry });
  const day = sample?.day ?? dayQuery.data;
  const leaderboard = sample ? { data: sample.leaderboard, failed: false } :
    { data: leaderboardQuery.data, failed: leaderboardQuery.isError };
  const record = sample ? { data: sample.record, failed: false } :
    { data: recordQuery.data, failed: recordQuery.isError };
  const noData = notFound(dayQuery.error);
  const dataAsOf = day ? day.data_as_of ? `Data k ${formatTimestamp(day.data_as_of)}` : "Zatím bez úspěšného sběru" : "";

  return <main>
    <header className="topbar"><div className="shell topbar-inner">
      <a className="brand" href="/" aria-label="Zpožděno, úvodní stránka"><span className="brand-mark">Z.</span><span>Zpožděno<span className="brand-period">.</span></span></a>
      <nav aria-label="Hlavní navigace"><a className="active" href="/">Přehled</a><a href="/metodika/">Metodika</a></nav>
      <a className="demo-link" href={demo ? "/" : "/?demo=1"}>{demo ? "Živý přehled" : "Ukázka webu"}<span aria-hidden> ↗</span></a>
    </div></header>

    <div className="shell">
      <div className="page-head"><div className="page-title"><span className="section-kicker">Pražská integrovaná doprava</span>
        <h1>{!date || date === today ? <>Jak moc dnes <i>trčíme?</i></> : <>Jak moc jsme <i>trčeli?</i></>}</h1></div>
        <div className="page-date"><label htmlFor="day-select" className="sr-only">Vyberte den</label>
          <input id="day-select" type="date" value={date} max={today} onChange={(event) => event.target.value && setDate(event.target.value)} />
          <small>{date ? formatDate(date) : "Načítáme datum"}{dataAsOf && ` · ${dataAsOf}`}</small></div></div>

      {demo && <div className="demo-notice"><strong>Ukázková data</strong><span>Tato čísla jsou vymyšlená pro návrh webu. Nejde o údaje PID.</span></div>}
      {!date || (!demo && dayQuery.isPending) ? <div className="state-panel">Načítáme přehled dne…</div> :
        day ? <Overview day={day} isToday={date === today} leaderboard={leaderboard} record={record} /> :
          <div className="state-panel"><span className="state-icon">∅</span><h2>{noData ? "Na tento den ještě nemáme data" : "Přehled se nepodařilo načíst"}</h2>
            <p>{noData ? "Sběr historie začne po spuštění serveru. Mezitím si můžete prohlédnout podobu dashboardu s jasně označenými ukázkovými čísly." : "Zkuste stránku za chvíli obnovit. Dostupnost zdroje ověříme znovu."}</p>
            {noData && <a className="button-link" href="/?demo=1">Zobrazit ukázku <span aria-hidden>↗</span></a>}
          </div>}

      <footer className="site-footer"><div><strong>Zpožděno.</strong><p>Neoficiální pohled na dochvilnost PID. Nejsme součástí PID ani DPP.</p></div>
        <div><p>Zdroj dat: Golemio, licence CC BY 4.0.</p><a href="/metodika/">Jak čísla počítáme →</a></div></footer>
    </div>
  </main>;
}
