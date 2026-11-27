import type { StudioDialogNode, StudioDialogReply } from '../../api/blueAiApi';

export function dialogReply(node: StudioDialogNode, locale: string): StudioDialogReply {
  if (node.translations != null) return node.translations[locale] ?? {};
  return locale === 'en' ? {
    responseText: node.englishResponseText,
    missingEntityPrompt: node.englishMissingEntityPrompt,
    irrelevantResponseText: node.englishIrrelevantResponseText,
  } : {};
}

export function patchDialogReply(node: StudioDialogNode, locale: string, patch: StudioDialogReply): Partial<StudioDialogNode> {
  const translations: Record<string, StudioDialogReply> = node.translations == null ? { en: dialogReply(node, 'en') } : { ...node.translations };
  const reply = { ...dialogReply(node, locale), ...patch };
  translations[locale] = reply;
  return {
    translations,
    ...(locale === 'en' ? {
      englishResponseText: reply.responseText ?? '',
      englishMissingEntityPrompt: reply.missingEntityPrompt ?? '',
      englishIrrelevantResponseText: reply.irrelevantResponseText ?? '',
    } : {}),
  };
}
