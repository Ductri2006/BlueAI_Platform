import { describe, expect, it } from 'vitest';
import { ragSourceToCitation } from './blueAiApi';

describe('product catalogue evidence', () => {
  it('presents product evidence as a SKU reference rather than a document chunk', () => {
    const citation = ragSourceToCitation({ documentId: 'product:TEST-JP-7D', filename: 'Japan 7 days', chunkId: 'TEST-JP-7D', excerpt: 'sellingPrice=80000 VND', sourceType: 'PRODUCT_CATALOG', approvalStatus: 'SYNCED' });
    expect(citation.section).toBe('SKU TEST-JP-7D');
    expect(citation.version).toBe('Danh mục sản phẩm đã đồng bộ');
    expect(citation.owner).toBe('Danh mục Bluecom');
    expect(citation.status).toBe('valid');
  });
});
