import type { Metadata } from "next";

export const metadata: Metadata = { title: "Metodika — Zpožděno" };

export default function Methodology() {
  return <main className="method-page"><div className="shell">
    <header className="method-header"><a className="brand" href="/"><span className="brand-mark">Z.</span><span>Zpožděno<span className="brand-period">.</span></span></a>
      <a href="/">← Zpět na přehled</a></header>
    <div className="method-intro"><span className="section-kicker">Metodika</span><h1>Co se skrývá<br />za čísly.</h1>
      <p>Všechna čísla odvozujeme ze zpoždění při odjezdu ze zastávky. Zdroj poloh vozidel je Golemio (CC BY 4.0); projekt není oficiální službou PID ani DPP.</p></div>
    <div className="method-grid">
      <section><span>01</span><h2>Kdy je odjezd včas?</h2><p>Odjezd v rozmezí 59 sekund před plánem až 179 sekund po plánu. Dřívější odjezd nepovažujeme za dochvilný, protože cestujícímu mohl spoj ujet.</p></section>
      <section><span>02</span><h2>Co znamená index?</h2><p>Podíl změřených odjezdů včas ze všech změřených odjezdů. Zastávky, na kterých zpoždění nezměříme, do jmenovatele nepřidáváme.</p></section>
      <section><span>03</span><h2>Součet zpoždění spojů</h2><p>Za každý spoj vezmeme jeho největší kladné zpoždění při odjezdu a tyto hodnoty sečteme. Nejde o součet času všech cestujících.</p></section>
      <section><span>04</span><h2>Neúplný den</h2><p>Při delším výpadku sběru označíme den jako neúplný. Čísla pak popisují pouze to, co se podařilo změřit.</p></section>
    </div><div className="method-note"><h2>Proč tu není metro?</h2><p>Data metra sbíráme, ale jejich zpoždění zatím nejsou ověřena proti skutečným mimořádnostem. Do veřejného přehledu je zařadíme až po ověření.</p></div>
    <footer className="site-footer"><div><strong>Zpožděno.</strong><p>Data: Golemio · CC BY 4.0</p></div><a href="/">Zpět na přehled →</a></footer>
  </div></main>;
}
