import type { Metadata } from "next";
import Footer from "@/components/layout/Footer";
import Navbar from "@/components/layout/Navbar";
import { AuthProvider } from "@/context/AuthContext";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "JobMatch",
    template: "%s | JobMatch",
  },
  description:
    "Find relevant and fresh jobs, understand your match, and spend less time on outdated listings.",
  icons: {
    icon: "/favicon.svg",
  },
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>
          <Navbar />
          <main className="app-main">{children}</main>
          <Footer />
        </AuthProvider>
      </body>
    </html>
  );
}
