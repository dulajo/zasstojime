import type { Metadata } from "next";
import { Providers } from "@/components/Providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Zpožděno — dochvilnost pražské dopravy",
  description: "Dochvilnost PID podle skutečných odjezdů ze zastávek. Každé číslo má vysvětlení.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="cs"><body><Providers>{children}</Providers></body></html>;
}
