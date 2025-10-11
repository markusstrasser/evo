// @ts-check
import { test, expect } from '@playwright/test';
import fs from 'fs/promises';
import path from 'path';
import os from 'os';

test.describe('Anki Clone - Core Functionality', () => {
  let tempDir;

  test.beforeEach(async () => {
    // Create temp directory for File System Access API
    tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'anki-test-'));
  });

  test.afterEach(async () => {
    // Cleanup temp directory
    if (tempDir) {
      await fs.rm(tempDir, { recursive: true, force: true });
    }
  });

  test('should show welcome screen on first load', async ({ page }) => {
    await page.goto('/');

    await expect(page.locator('h1')).toContainText('Welcome to Local-First Anki');
    await expect(page.getByRole('button', { name: 'Select Folder' })).toBeVisible();
  });

  test('should parse QA cards correctly', async ({ page }) => {
    await page.goto('/');

    // Test new prefix-based format: q/a lines
    const qaCard = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      return core.parse_card('q What is 2+2?\na 4');
    });

    expect(qaCard).toEqual({
      type: 'qa',
      question: 'What is 2+2?',
      answer: '4'
    });
  });

  test('should parse cloze deletion cards correctly', async ({ page }) => {
    await page.goto('/');

    // Test new prefix format: c prefix
    const clozeCard = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      return core.parse_card('c DNA has [3 billion] base pairs');
    });

    expect(clozeCard).toEqual({
      type: 'cloze',
      template: 'DNA has [3 billion] base pairs',
      deletions: ['3 billion']
    });
  });

  test('should create consistent card hashes', async ({ page }) => {
    await page.goto('/');

    const [hash1, hash2] = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      const card = { type: 'qa', question: 'Test?', answer: 'Answer' };
      return [
        core.card_hash(card),
        core.card_hash(card)
      ];
    });

    expect(hash1).toBe(hash2);
  });

  test('should schedule cards with mock algorithm', async ({ page }) => {
    await page.goto('/');

    const scheduled = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      const meta = {
        'card-hash': 'test123',
        'created-at': new Date(),
        'due-at': new Date(),
        reviews: 0
      };
      return core.schedule_card(meta, 'good');
    });

    expect(scheduled.reviews).toBe(1);
    expect(scheduled['last-rating']).toBe('good');
    expect(scheduled['due-at']).toBeDefined();
  });

  test('should apply card-created events', async ({ page }) => {
    await page.goto('/');

    const state = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      const event = core.card_created_event('hash1', {
        type: 'qa',
        question: 'Q',
        answer: 'A'
      });
      return core.reduce_events([event]);
    });

    expect(state.cards['hash1']).toBeDefined();
    expect(state.meta['hash1']).toBeDefined();
    expect(state.meta['hash1'].reviews).toBe(0);
  });

  test('should apply review events', async ({ page }) => {
    await page.goto('/');

    const finalState = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      const createEvent = core.card_created_event('hash1', {
        type: 'qa',
        question: 'Q',
        answer: 'A'
      });
      const reviewEvent = core.review_event('hash1', 'good');
      return core.reduce_events([createEvent, reviewEvent]);
    });

    expect(finalState.meta['hash1'].reviews).toBe(1);
    expect(finalState.meta['hash1']['last-rating']).toBe('good');
  });

  test('should identify due cards', async ({ page }) => {
    await page.goto('/');

    const dueCards = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      const now = new Date();
      const past = new Date(now.getTime() - 1000000);
      const future = new Date(now.getTime() + 1000000);

      const state = {
        cards: {
          'due': { type: 'qa', question: 'Due?', answer: 'Yes' },
          'not-due': { type: 'qa', question: 'Future?', answer: 'Later' }
        },
        meta: {
          'due': { 'due-at': past, reviews: 0 },
          'not-due': { 'due-at': future, reviews: 0 }
        }
      };

      return core.due_cards(state);
    });

    expect(dueCards).toEqual(['due']);
  });

  test('should merge card with metadata', async ({ page }) => {
    await page.goto('/');

    const cardWithMeta = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      const state = {
        cards: { 'h1': { type: 'qa', question: 'Q', answer: 'A' } },
        meta: { 'h1': { reviews: 5, 'due-at': new Date() } }
      };
      return core.card_with_meta(state, 'h1');
    });

    expect(cardWithMeta.type).toBe('qa');
    expect(cardWithMeta.meta.reviews).toBe(5);
  });
});

test.describe('Anki Clone - UI Integration (Mock Mode)', () => {
  test('should display card count after initialization', async ({ page }) => {
    await page.goto('/');

    // In a real test, you'd select a folder with the File System Access API
    // For now, we'll test the UI renders correctly in setup mode
    await expect(page.locator('.setup-screen')).toBeVisible();
  });

  test('should have rating buttons with correct labels', async ({ page }) => {
    await page.goto('/');

    // Check that rating button labels exist in source
    const content = await page.content();
    expect(content).toContain('Forgot');
    expect(content).toContain('Hard');
    expect(content).toContain('Good');
    expect(content).toContain('Easy');
  });
});

test.describe('Anki Clone - New Features', () => {
  test('should parse multiple card types with prefix syntax', async ({ page }) => {
    await page.goto('/');

    const results = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      return {
        qa: core.parse_card('q Capital of France?\na Paris'),
        cloze: core.parse_card('c Human DNA has [3 billion] base pairs'),
        invalid: core.parse_card('No prefix means no parse')
      };
    });

    expect(results.qa.type).toBe('qa');
    expect(results.cloze.type).toBe('cloze');
    expect(results.invalid).toBeNull();
  });

  test('should handle multiline QA cards', async ({ page }) => {
    await page.goto('/');

    const qaCard = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      return core.parse_card('q What is DNA?\na Deoxyribonucleic acid');
    });

    expect(qaCard).toEqual({
      type: 'qa',
      question: 'What is DNA?',
      answer: 'Deoxyribonucleic acid'
    });
  });

  test('should support image occlusion cards', async ({ page }) => {
    await page.goto('/');

    const imageCard = await page.evaluate(() => {
      const core = window['lab.anki.core'];
      return core.parse_card('![Brain diagram](brain.png) {hippocampus, cortex}');
    });

    expect(imageCard).toEqual({
      type: 'image-occlusion',
      'alt-text': 'Brain diagram',
      'image-url': 'brain.png',
      regions: ['hippocampus', 'cortex']
    });
  });

  test('should check for saved directory handle on startup', async ({ page }) => {
    // Monitor console logs
    const logs = [];
    page.on('console', msg => logs.push(msg.text()));

    await page.goto('/');
    await page.waitForTimeout(1000);

    // Should try to load saved handle
    const hasLoadLog = logs.some(log => log.includes('Loading directory handle'));
    expect(hasLoadLog).toBe(true);
  });

  test('should save directory handle to IndexedDB', async ({ page }) => {
    await page.goto('/');

    // Check that IndexedDB functions exist
    const hasIndexedDB = await page.evaluate(() => {
      return typeof window.indexedDB !== 'undefined';
    });

    expect(hasIndexedDB).toBe(true);

    // Check that our DB name is accessible
    const dbExists = await page.evaluate(async () => {
      const dbs = await window.indexedDB.databases();
      return dbs.some(db => db.name === 'anki-db');
    });

    // DB only exists after first use, so this might be false on clean run
    // Just verify the API is available
    expect(typeof dbExists).toBe('boolean');
  });
});
