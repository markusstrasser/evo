// @ts-check
import { test, expect } from '@playwright/test';

/**
 * End-to-end tests for the Anki clone application.
 *
 * Structure:
 * - Core API tests: Direct function calls to test business logic
 * - UI Integration tests: Visual rendering and interaction
 */

test.describe('Anki - Core API', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/public/anki.html');
    await page.waitForSelector('.anki-app');
  });

  test.describe('Card Parsing', () => {
    test('should parse QA cards with q/a prefix format', async ({ page }) => {
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

    test('should parse cloze deletion cards with c prefix', async ({ page }) => {
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

    test('should parse image occlusion cards', async ({ page }) => {
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

    test('should return null for invalid cards', async ({ page }) => {
      const result = await page.evaluate(() => {
        const core = window['lab.anki.core'];
        return core.parse_card('No prefix means no parse');
      });

      expect(result).toBeNull();
    });

    test('should handle multiple cloze deletions', async ({ page }) => {
      const card = await page.evaluate(() => {
        const core = window['lab.anki.core'];
        return core.parse_card('c The [mitochondria] is the [powerhouse] of the cell');
      });

      expect(card.deletions).toEqual(['mitochondria', 'powerhouse']);
    });
  });

  test.describe('Card Hashing', () => {
    test('should produce consistent hashes for identical cards', async ({ page }) => {
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

    test('should produce different hashes for different cards', async ({ page }) => {
      const [hash1, hash2] = await page.evaluate(() => {
        const core = window['lab.anki.core'];
        const card1 = { type: 'qa', question: 'Q1', answer: 'A1' };
        const card2 = { type: 'qa', question: 'Q2', answer: 'A2' };
        return [core.card_hash(card1), core.card_hash(card2)];
      });

      expect(hash1).not.toBe(hash2);
    });
  });

  test.describe('Event Sourcing', () => {
    test('should create and apply card-created events', async ({ page }) => {
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

    test('should create and apply review events', async ({ page }) => {
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

    test('should reduce multiple events correctly', async ({ page }) => {
      const state = await page.evaluate(() => {
        const core = window['lab.anki.core'];
        const events = [
          core.card_created_event('h1', { type: 'qa', question: 'Q1', answer: 'A1' }),
          core.card_created_event('h2', { type: 'qa', question: 'Q2', answer: 'A2' }),
          core.review_event('h1', 'good'),
          core.review_event('h1', 'easy')
        ];
        return core.reduce_events(events);
      });

      expect(Object.keys(state.cards).length).toBe(2);
      expect(state.meta['h1'].reviews).toBe(2);
      expect(state.meta['h2'].reviews).toBe(0);
    });
  });

  test.describe('Scheduling', () => {
    test('should schedule cards with mock algorithm', async ({ page }) => {
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

    test('should identify due cards correctly', async ({ page }) => {
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
  });

  test.describe('Queries', () => {
    test('should merge card with metadata', async ({ page }) => {
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
});

test.describe('Anki - UI Integration', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/public/anki.html');
    await page.waitForSelector('.anki-app');
  });

  test.describe('Initial Render', () => {
    test('should display setup screen on first load', async ({ page }) => {
      await expect(page.locator('.setup-screen')).toBeVisible();
      await expect(page.locator('h1')).toContainText('Welcome to Local-First Anki');
      await expect(page.getByRole('button', { name: 'Select Folder' })).toBeVisible();
    });

    test('should render proper navigation header', async ({ page }) => {
      await expect(page.locator('nav h1')).toContainText('Local-First Anki');
      await expect(page.locator('nav p')).toContainText('Edit cards.md');
    });

    test('should render components as DOM elements, not strings', async ({ page }) => {
      const bodyText = await page.textContent('body');
      expect(bodyText).not.toContain('#object');
      expect(bodyText).not.toContain('lab$anki');
    });

    test('should have styled components with CSS loaded', async ({ page }) => {
      const appBackground = await page.locator('.anki-app').evaluate(el =>
        window.getComputedStyle(el).backgroundColor
      );
      expect(appBackground).toBe('rgb(255, 255, 255)');
    });
  });

  test.describe('Component Structure', () => {
    test('should have rating buttons with correct labels', async ({ page }) => {
      const content = await page.content();
      expect(content).toContain('Forgot');
      expect(content).toContain('Hard');
      expect(content).toContain('Good');
      expect(content).toContain('Easy');
    });

    test('should render button as actual button element', async ({ page }) => {
      const button = await page.locator('.setup-screen button');
      await expect(button).toBeVisible();
      expect(await button.evaluate(el => el.tagName)).toBe('BUTTON');
    });
  });

  test.describe('Console Logging', () => {
    test('should not have errors on load', async ({ page }) => {
      const consoleErrors = [];
      page.on('console', msg => {
        if (msg.type() === 'error') {
          consoleErrors.push(msg.text());
        }
      });

      await page.goto('/public/anki.html');
      await page.waitForSelector('.anki-app');

      const errorText = consoleErrors.join(' ');
      expect(errorText).not.toContain('Cannot read properties');
      expect(errorText).not.toContain('undefined is not');
      expect(errorText).not.toContain('is not a function');

      if (consoleErrors.length > 0) {
        console.log('Console errors:', consoleErrors);
      }
    });

    test('should log Replicant initialization', async ({ page }) => {
      const consoleLogs = [];
      page.on('console', msg => {
        if (msg.type() === 'log') {
          consoleLogs.push(msg.text());
        }
      });

      await page.goto('/public/anki.html');
      await page.waitForSelector('.anki-app');

      const logText = consoleLogs.join(' ');
      expect(logText).toContain('Anki app starting with Replicant');
    });
  });

  test.describe('IndexedDB Integration', () => {
    test('should have IndexedDB available', async ({ page }) => {
      const hasIndexedDB = await page.evaluate(() => {
        return typeof window.indexedDB !== 'undefined';
      });

      expect(hasIndexedDB).toBe(true);
    });

    test('should attempt to load saved directory handle on startup', async ({ page }) => {
      const logs = [];
      page.on('console', msg => logs.push(msg.text()));

      await page.goto('/public/anki.html');
      await page.waitForTimeout(1000);

      const hasLoadLog = logs.some(log => log.includes('Loading directory handle'));
      expect(hasLoadLog).toBe(true);
    });
  });
});
