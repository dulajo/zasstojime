"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { api, ApiError, type Day, type Leaderboard, type RecordDay, type RouteRank } from "@/lib/api";
import { demoData } from "@/lib/demo-data";
import { formatDate, formatDuration, formatNumber, formatPercent, formatTimestamp, todayInPrague } from "@/lib/format";

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

function Overview({ day, leaderboard, record }: {
  day: Day; leaderboard?: Leaderboard; record?: RecordDay;
}) {
  const categories = Object.entries(day.categories).filter(([key]) => key !== "metro");
  return <>
    {day.incomplete && <div className="notice">Sběr měl dnes delší výpadek. Uvedená čísla jsou neúplná.</div>}
    <section aria-labelledby="overview-title" className="section-block">
      <div className="section-heading"><div><span className="section-kicker">Přehled dne</span><h2 id="overview-title">Co ukazují odjezdy</h2></div>
        <p>Dochvilnost počítáme při odjezdu z každé zastávky, ne až na konečné.</p></div>
      <div className="metric-grid">
        <Metric label="Index dochvilnosti" value={formatPercent(day.network.punctuality_pct)} note={`${formatNumber(day.network.dep_on_time)} z ${formatNumber(day.network.dep_total)} odjezdů včas`} accent />
        <Metric label="Součet zpoždění spojů" value={formatDuration(day.network.sum_max_delay)} note="Součet největšího zpoždění každého spoje" />
        <Metric label="Průměr zpožděného odjezdu" value={formatDuration(day.network.average_late_delay_s)} note={`${formatNumber(day.network.dep_late)} zpožděných odjezdů`} />
        <Metric label="Právě na trase" value={day.running_trips == null ? "—" : formatNumber(day.running_trips)} note={day.running_late_trips == null ? "Živý stav není dostupný" : `${formatNumber(day.running_late_trips)} spojů má zpoždění`} />
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
      {leaderboard ? <div className="ranking-grid">
        <Ranking title="Největší součet" subtitle="Zpoždění spojů na lince" items={leaderboard.most_total_delay} value={(item) => formatDuration(item.totals.sum_max_delay)} />
        <Ranking title="Nejvíc na spoj" subtitle="Průměrné maximum spoje" items={leaderboard.highest_delay_per_trip} value={(item) => formatDuration(item.average_max_delay_s)} />
        <Ranking title="Nejhorší dochvilnost" subtitle="Podíl odjezdů včas" items={leaderboard.worst_punctuality} value={(item) => formatPercent(item.totals.punctuality_pct)} />
      </div> : <div className="empty-panel">Žebříčky se zatím nepodařilo načíst.</div>}
    </section>

    <section aria-labelledby="record-title" className="record-panel">
      <div><span className="section-kicker">Rekordman dne</span><h2 id="record-title">Největší zpoždění na trase</h2>
        <p>Bereme nejvyšší změřené zpoždění odjezdu, ne průběžný odhad polohy vozidla.</p></div>
      {record?.record ? <div className="record-result">
        <span>Linka {record.record.line}{record.record.headsign ? ` · ${record.record.headsign}` : ""}</span>
        <strong>+{formatDuration(record.record.delay_s)}</strong>
        {record.record.flagged && !record.record.verified && <em>Neověřeno</em>}
      </div> : <div className="record-result"><span>Rekord pro tento den zatím nemáme.</span></div>}
    </section>
  </>;
}

export function Dashboard() {
  const [date, setDate] = useState("");
  const [demo, setDemo] = useState(false);
  useEffect(() => {
    setDate(todayInPrague());
    setDemo(new URLSearchParams(window.location.search).get("demo") === "1");
  }, []);
  const sample = useMemo(() => date && demo ? demoData(date) : null, [date, demo]);
  const dayQuery = useQuery({ queryKey: ["day", date], queryFn: ({ signal }) => api.day(date, signal),
    enabled: Boolean(date) && !demo, refetchInterval: 30_000 });
  const leaderboardQuery = useQuery({ queryKey: ["leaderboard", date], queryFn: ({ signal }) => api.leaderboard(date, signal),
    enabled: Boolean(dayQuery.data) && !demo, refetchInterval: 30_000 });
  const recordQuery = useQuery({ queryKey: ["record", date], queryFn: ({ signal }) => api.record(date, signal),
    enabled: Boolean(dayQuery.data) && !demo, refetchInterval: 30_000 });
  const day = sample?.day ?? dayQuery.data;
  const leaderboard = sample?.leaderboard ?? leaderboardQuery.data;
  const record = sample?.record ?? recordQuery.data;
  const noData = dayQuery.error instanceof ApiError && dayQuery.error.status === 404;

  return <main>
    <header className="topbar"><div className="shell topbar-inner">
      <a className="brand" href="/" aria-label="Zpožděno, úvodní stránka"><span className="brand-mark">Z.</span><span>Zpožděno<span className="brand-period">.</span></span></a>
      <nav aria-label="Hlavní navigace"><a className="active" href="/">Přehled</a><a href="/metodika/">Metodika</a></nav>
      <a className="demo-link" href={demo ? "/" : "/?demo=1"}>{demo ? "Živý přehled" : "Ukázka webu"}<span aria-hidden> ↗</span></a>
    </div></header>

    <div className="shell">
      <section className="hero"><div className="hero-copy"><div className="hero-kicker"><span className="live-dot" /> PRAŽSKÁ INTEGROVANÁ DOPRAVA</div>
        <h1>Jak moc dnes<br /><i>trčíme?</i></h1>
        <p>Dochvilnost pražské dopravy podle skutečných odjezdů ze zastávek. Bez dojmů, s vysvětlením každého čísla.</p>
      </div><div className="hero-side"><span>01 / Přehled sítě</span><strong>Data místo odhadů.</strong><small>Zdroj: Golemio · CC BY 4.0</small></div></section>

      <div className="toolbar"><div><span className="section-kicker">Provozní den</span><label htmlFor="day-select" className="sr-only">Vyberte den</label>
        <input id="day-select" type="date" value={date} max={todayInPrague()} onChange={(event) => setDate(event.target.value)} /></div>
        <div className="toolbar-meta"><span>{date ? formatDate(date) : "Načítáme datum"}</span><small>Data k {formatTimestamp(day?.data_as_of ?? null)}</small></div></div>

      {demo && <div className="demo-notice"><strong>Ukázková data</strong><span>Tato čísla jsou vymyšlená pro návrh webu. Nejde o údaje PID.</span></div>}
      {!date || (!demo && dayQuery.isPending) ? <div className="state-panel">Načítáme přehled dne…</div> :
        day ? <Overview day={day} leaderboard={leaderboard} record={record} /> :
          <div className="state-panel"><span className="state-icon">∅</span><h2>{noData ? "Na tento den ještě nemáme data" : "Přehled se nepodařilo načíst"}</h2>
            <p>{noData ? "Sběr historie začne po spuštění serveru. Mezitím si můžete prohlédnout podobu dashboardu s jasně označenými ukázkovými čísly." : "Zkuste stránku za chvíli obnovit. Dostupnost zdroje ověříme znovu."}</p>
            {noData && <a className="button-link" href="/?demo=1">Zobrazit ukázku <span aria-hidden>↗</span></a>}
          </div>}

      <footer className="site-footer"><div><strong>Zpožděno.</strong><p>Neoficiální pohled na dochvilnost PID. Nejsme součástí PID ani DPP.</p></div>
        <div><p>Zdroj dat: Golemio, licence CC BY 4.0.</p><a href="/metodika/">Jak čísla počítáme →</a></div></footer>
    </div>
  </main>;
}
