"use client";

import { useMemo } from "react";
import Link from "next/link";
import {
  BookA,
  CheckCircle2,
  CircleDashed,
  FolderGit2,
  Loader2,
  XCircle,
  Code2,
  ArrowRight,
  DatabaseZap,
  FileText,
} from "lucide-react";

import { Card, CardContent } from "@/components/ui/card";
import { useCurrentUser } from "@/hooks/use-auth";
import { useRepos } from "@/hooks/use-repo";
import type { IndexStatus, Repository } from "@/lib/api";
import { LanguageBadge } from "@/components/dashboard/language-badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { cn } from "@/lib/utils";

type StatItem = {
  label: string;
  value: string | number;
  icon: React.ComponentType<{ className?: string }>;
  tone: "slate" | "emerald" | "amber" | "sky" | "rose";
};

const toneClasses: Record<StatItem["tone"], string> = {
  slate:
    "bg-slate-100 text-slate-700 dark:bg-slate-900/60 dark:text-slate-200",
  emerald:
    "bg-emerald-100 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300",
  amber:
    "bg-amber-100 text-amber-700 dark:bg-amber-950/60 dark:text-amber-300",
  sky: "bg-sky-100 text-sky-700 dark:bg-sky-950/60 dark:text-sky-300",
  rose: "bg-rose-100 text-rose-700 dark:bg-rose-950/60 dark:text-rose-300",
};

function countByStatus(repos: Repository[]) {
  const counts: Record<IndexStatus, number> = {
    PENDING: 0,
    INDEXING: 0,
    READY: 0,
    FAILED: 0,
  };
  for (const r of repos) counts[r.indexStatus]++;
  return counts;
}

function topLanguages(repos: Repository[]) {
  const map = new Map<string, number>();
  for (const r of repos) {
    if (!r.language) continue;
    map.set(r.language, (map.get(r.language) ?? 0) + 1);
  }
  return Array.from(map.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6);
}

export function OverviewDashboard() {
  const { data: user } = useCurrentUser();
  const reposQuery = useRepos();
  const repos = reposQuery.data ?? [];

  const stats = useMemo<StatItem[]>(() => {
    const counts = countByStatus(repos);
    const totalChunks = repos.reduce((acc, r) => acc + (r.chunkCount ?? 0), 0);
    const totalFiles = repos.reduce(
      (acc, r) => acc + (r.filesTotal ?? 0),
      0
    );
    return [
      {
        label: "Repositories",
        value: repos.length,
        icon: FolderGit2,
        tone: "slate",
      },
      {
        label: "Indexed",
        value: counts.READY,
        icon: CheckCircle2,
        tone: "emerald",
      },
      {
        label: "Indexing",
        value: counts.INDEXING,
        icon: Loader2,
        tone: "sky",
      },
      {
        label: "Failed",
        value: counts.FAILED,
        icon: XCircle,
        tone: "rose",
      },
      {
        label: "Code chunks",
        value: totalChunks.toLocaleString(),
        icon: DatabaseZap,
        tone: "amber",
      },
      {
        label: "Source files",
        value: totalFiles.toLocaleString(),
        icon: FileText,
        tone: "slate",
      },
    ];
  }, [repos]);

  const languages = useMemo(() => topLanguages(repos), [repos]);

  const recentRepos = useMemo(() => {
    return [...repos]
      .sort((a, b) => {
        const aTime = a.indexedAt ? new Date(a.indexedAt).getTime() : 0;
        const bTime = b.indexedAt ? new Date(b.indexedAt).getTime() : 0;
        return bTime - aTime;
      })
      .slice(0, 4);
  }, [repos]);

  return (
    <div className="flex flex-col gap-6 p-4 md:p-6">
      <div>
        <p className="text-xs text-muted-foreground">
          Welcome back
        </p>
        <h1 className="mt-1 font-heading text-2xl font-semibold tracking-tight">
          Hey, {user?.displayName ?? user?.githubUsername ?? "there"} 👋
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Here is what is happening with your code workspace today.
        </p>
      </div>

      {reposQuery.isLoading && (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-28 rounded-2xl" />
          ))}
        </div>
      )}

      {!reposQuery.isLoading && (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {stats.map((stat) => (
            <Card key={stat.label} className="overflow-hidden border-dashed">
              <CardContent className="flex items-start gap-4 p-5">
                <div
                  className={cn(
                    "flex size-11 shrink-0 items-center justify-center rounded-2xl",
                    toneClasses[stat.tone]
                  )}
                >
                  <stat.icon className="size-5" />
                </div>
                <div className="min-w-0">
                  <p className="text-xs text-muted-foreground">{stat.label}</p>
                  <p className="mt-1 font-heading text-2xl font-semibold tracking-tight">
                    {stat.value}
                  </p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-5">
        <section className="lg:col-span-3">
          <div className="mb-3 flex items-end justify-between gap-3">
            <div>
              <h2 className="font-heading text-lg font-semibold tracking-tight">
                Recent repositories
              </h2>
              <p className="text-sm text-muted-foreground">
                Jump back in to continue chatting.
              </p>
            </div>
            <Button
              size="sm"
              variant="outline"
              render={<Link href="/dashboard" />}
            >
              All repos <ArrowRight className="size-4" />
            </Button>
          </div>

          {reposQuery.isLoading ? (
            <div className="grid gap-4 md:grid-cols-2">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-40 rounded-2xl" />
              ))}
            </div>
          ) : recentRepos.length === 0 ? (
            <Empty className="border border-dashed">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <BookA />
                </EmptyMedia>
                <EmptyTitle>No repositories yet</EmptyTitle>
                <EmptyDescription>
                  Head to Repositories to sync your GitHub account.
                </EmptyDescription>
              </EmptyHeader>
              <Button render={<Link href="/dashboard" />}>
                Go to Repositories
              </Button>
            </Empty>
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {recentRepos.map((repo) => (
                <Card
                  key={repo.id}
                  className="group overflow-hidden border-dashed transition hover:border-foreground/20 hover:shadow-sm"
                >
                  <CardContent className="flex flex-col gap-4 p-5">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 text-muted-foreground">
                          <FolderGit2 className="size-4 shrink-0" />
                          <span className="truncate text-xs">
                            {repo.owner}
                          </span>
                        </div>
                        <Link
                          href={`/chat/${repo.id}`}
                          className="mt-1 block truncate font-heading text-base font-semibold tracking-tight group-hover:underline"
                        >
                          {repo.name}
                        </Link>
                      </div>
                      <StatusBadge status={repo.indexStatus} />
                    </div>
                    <p className="line-clamp-2 min-h-[2.5rem] text-sm text-muted-foreground">
                      {repo.description || "No description provided."}
                    </p>
                    <div className="flex items-center justify-between gap-3">
                      <LanguageBadge language={repo.language} />
                      <Button
                        size="sm"
                        disabled={repo.indexStatus !== "READY"}
                        render={<Link href={`/chat/${repo.id}`} />}
                      >
                        <Code2 className="size-4" /> Chat
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          )}
        </section>

        <section className="lg:col-span-2">
          <div className="mb-3">
            <h2 className="font-heading text-lg font-semibold tracking-tight">
              Languages
            </h2>
            <p className="text-sm text-muted-foreground">
              Your most-indexed codebases by language.
            </p>
          </div>
          <Card className="border-dashed">
            <CardContent className="p-5">
              {reposQuery.isLoading ? (
                <div className="flex flex-col gap-3">
                  {Array.from({ length: 6 }).map((_, i) => (
                    <Skeleton key={i} className="h-10 w-full rounded-lg" />
                  ))}
                </div>
              ) : languages.length === 0 ? (
                <Empty>
                  <EmptyHeader>
                    <EmptyMedia variant="icon">
                      <Code2 />
                    </EmptyMedia>
                    <EmptyTitle>No language data</EmptyTitle>
                    <EmptyDescription>
                      Index some repos to see a breakdown.
                    </EmptyDescription>
                  </EmptyHeader>
                </Empty>
              ) : (
                <ul className="flex flex-col gap-2">
                  {languages.map(([language, count]) => {
                    const max = Math.max(
                      ...languages.map(([, v]) => v),
                      1
                    );
                    const width = `${(count / max) * 100}%`;
                    return (
                      <li key={language} className="flex flex-col gap-2">
                        <div className="flex items-center justify-between text-sm">
                          <div className="flex items-center gap-2">
                            <LanguageBadge language={language} />
                            <span className="text-muted-foreground">
                              {language}
                            </span>
                          </div>
                          <span className="tabular-nums text-muted-foreground">
                            {count} {count === 1 ? "repo" : "repos"}
                          </span>
                        </div>
                        <div className="h-2 overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-foreground/70"
                            style={{ width }}
                          />
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </CardContent>
          </Card>
        </section>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: IndexStatus }) {
  switch (status) {
    case "READY":
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300">
          <CheckCircle2 className="size-3" /> Ready
        </span>
      );
    case "INDEXING":
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-sky-50 px-2 py-0.5 text-xs font-medium text-sky-700 dark:bg-sky-950/50 dark:text-sky-300">
          <Loader2 className="size-3 animate-spin" /> Indexing
        </span>
      );
    case "FAILED":
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-rose-50 px-2 py-0.5 text-xs font-medium text-rose-700 dark:bg-rose-950/50 dark:text-rose-300">
          <XCircle className="size-3" /> Failed
        </span>
      );
    case "PENDING":
    default:
      return (
        <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700 dark:bg-slate-900/60 dark:text-slate-200">
          <CircleDashed className="size-3" /> Pending
        </span>
      );
  }
}
