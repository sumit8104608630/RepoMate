"use client";

import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Avatar,
  AvatarFallback,
  AvatarImage,
} from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import { GitHubIcon } from "@/components/icons/github-icon";
import { useCurrentUser, useLogout } from "@/hooks/use-auth";
import { getApiBaseUrl } from "@/lib/api";

function initials(name: string | null | undefined) {
  return (name ?? "DP").slice(0, 2).toUpperCase();
}

export function SettingDashboard() {
  const { data: user, isLoading, isError } = useCurrentUser();
  const logout = useLogout();

  return (
    <div className="flex flex-col gap-6 p-4 md:p-6">
      <div>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">
          Settings
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Manage your account and connections.
        </p>
      </div>

      <section className="max-w-2xl">
        <Card className="border-dashed">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitHubIcon className="size-4" /> GitHub account
            </CardTitle>
            <CardDescription>
              Connected to DevPilot using OAuth. Repositories are pulled from
              the account below.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <div className="flex items-center gap-4">
                <Skeleton className="size-14 rounded-2xl" />
                <div className="flex flex-col gap-2">
                  <Skeleton className="h-5 w-48 rounded" />
                  <Skeleton className="h-4 w-36 rounded" />
                </div>
              </div>
            ) : isError || !user ? (
              <p className="text-sm text-muted-foreground">
                Could not load your account. Sign in again to continue using
                DevPilot.
              </p>
            ) : (
              <div className="flex items-start gap-4">
                <Avatar className="size-14 rounded-2xl">
                  <AvatarImage src={user.avatarUrl ?? undefined} />
                  <AvatarFallback className="rounded-2xl text-lg">
                    {initials(user.displayName)}
                  </AvatarFallback>
                </Avatar>
                <div className="flex min-w-0 flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="truncate font-heading text-base font-semibold tracking-tight">
                      {user.displayName}
                    </p>
                    <Badge variant="secondary" className="gap-1">
                      <GitHubIcon className="size-3" /> Connected
                    </Badge>
                  </div>
                  <p className="truncate text-sm text-muted-foreground">
                    @{user.githubUsername}
                  </p>
                  <p className="truncate text-xs text-muted-foreground">
                    GitHub ID {user.githubId}
                  </p>
                </div>
              </div>
            )}
          </CardContent>
          <CardFooter className="flex flex-col items-start gap-2 border-t bg-muted/30 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-xs text-muted-foreground">
              Backend: <span className="font-mono">{getApiBaseUrl()}</span>
            </p>
            <div className="flex w-full items-center gap-2 sm:w-auto">
              <Button
                variant="outline"
                onClick={() => logout.mutate()}
                disabled={logout.isPending}
              >
                {logout.isPending ? "Signing out…" : "Sign out"}
              </Button>
            </div>
          </CardFooter>
        </Card>
      </section>
    </div>
  );
}
