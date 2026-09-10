"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback, useRef, useState } from "react";

import { api, type ChatMessage } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { streamChatMessage } from "@/lib/stream-chat";
import { toast } from "@/components/ui/toast";

export function useChatSessions(repositoryId: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.chat.sessions(repositoryId),
    queryFn: () => api.listSessions(repositoryId),
    enabled: Boolean(repositoryId) && enabled,
  });
}

export function useChatMessages(sessionId: string | null) {
  return useQuery({
    queryKey: queryKeys.chat.messages(sessionId ?? ""),
    queryFn: () => api.getMessages(sessionId!),
    enabled: Boolean(sessionId),
  });
}

export function useCreateChatSession(repositoryId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (title?: string) => api.createSession(repositoryId, title),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.chat.sessions(repositoryId),
      });
      queryClient.setQueryData(queryKeys.chat.messages(session.id), []);
    },
    onError: (error: Error) => {
      toast.add({
        title: "Could not create chat",
        description: error.message,
        type: "error",
      });
    },
  });
}

export function useStreamChat(sessionId: string | null) {
  const queryClient = useQueryClient();
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState("");
  const abortRef = useRef<AbortController | null>(null);
  const didFinalRefetchRef = useRef(false);

  const ensureMessagesRefetched = useCallback(async () => {
    if (!sessionId) return;
    const key = queryKeys.chat.messages(sessionId);
    try {
      await queryClient.invalidateQueries({
        queryKey: key,
        refetchType: "active",
      });
      await queryClient.refetchQueries({
        queryKey: key,
        type: "active",
        exact: true,
      });
    } catch {
      // ignore — cache still contains the optimistic / setQueryData copy
    }
  }, [sessionId, queryClient]);

  const send = useCallback(
    async (content: string) => {
      if (!sessionId || !content.trim() || streaming) return;

      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      didFinalRefetchRef.current = false;

      const optimisticId = `temp-${Date.now()}`;
      const optimistic: ChatMessage = {
        id: optimisticId,
        role: "USER",
        content: content.trim(),
        citations: [],
        createdAt: new Date().toISOString(),
      };

      queryClient.setQueryData<ChatMessage[]>(
        queryKeys.chat.messages(sessionId),
        (prev) => [...(prev ?? []), optimistic]
      );

      setStreaming(true);
      setStreamText("");

      try {
        await streamChatMessage(sessionId, content.trim(), {
          signal: controller.signal,
          onUserMessage: (message) => {
            queryClient.setQueryData<ChatMessage[]>(
              queryKeys.chat.messages(sessionId),
              (prev) => [
                ...(prev ?? []).filter((m) => m.id !== optimisticId),
                message,
              ]
            );
          },
          onToken: (token) => {
            setStreamText((prev) => prev + token);
          },
          onAssistantMessage: (message) => {
            queryClient.setQueryData<ChatMessage[]>(
              queryKeys.chat.messages(sessionId),
              (prev) => {
                const base = (prev ?? []).filter((m) => m.id !== optimisticId);
                if (base.some((m) => m.id === message.id)) return base;
                return [...base, message];
              }
            );
            setStreamText("");
            didFinalRefetchRef.current = true;
            void ensureMessagesRefetched();
          },
          onDone: () => {
            if (!didFinalRefetchRef.current) {
              didFinalRefetchRef.current = true;
              void ensureMessagesRefetched();
            }
          },
          onError: () => {
            if (!didFinalRefetchRef.current) {
              didFinalRefetchRef.current = true;
              void ensureMessagesRefetched();
            }
          },
        });
      } catch (err) {
        if ((err as Error).name === "AbortError") {
          // ensure we still see any partially-saved messages after user stopped
          if (!didFinalRefetchRef.current) {
            didFinalRefetchRef.current = true;
            void ensureMessagesRefetched();
          }
          return;
        }
        toast.add({
          title: "Message failed",
          description: err instanceof Error ? err.message : "Unknown error",
          type: "error",
        });
        queryClient.setQueryData<ChatMessage[]>(
          queryKeys.chat.messages(sessionId),
          (prev) => (prev ?? []).filter((m) => m.id !== optimisticId)
        );
        setStreamText("");
        if (!didFinalRefetchRef.current) {
          didFinalRefetchRef.current = true;
          void ensureMessagesRefetched();
        }
      } finally {
        setStreaming(false);
      }
    },
    [sessionId, streaming, queryClient, ensureMessagesRefetched]
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
    setStreaming(false);
  }, []);

  return { send, stop, streaming, streamText };
}