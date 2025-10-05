# Audio Package Test Checklist

## Setup
1. Open Chrome DevTools (F12)
2. Navigate to http://localhost:8080/lab.html
3. Keep Console tab open to see debug logs

## Visual Checks

### Initial Load
- [ ] Page background is black (#060606)
- [ ] Title shows "Polyphonic Pitch Detector"
- [ ] Test Mode section visible with 6 buttons (c-major, d-minor, etc.)
- [ ] "Initialize Audio" button visible
- [ ] Canvas container visible (900px × 400px, black background)

### Console Output on Load
Expected logs:
```
Canvas initialized: 900 x 400
```

## Test Mode Verification

### Test 1: C Major Chord
1. Click "c-major" button
2. **Console should show:**
   ```
   Loading test data: c-major
   Test data generated, bin count: 500
   ```

3. **Visual expectations:**
   - Spectrum display with colored vertical bars (OKLCH colors)
   - Three note labels visible: C, E, G
   - Chord display shows "C" with light background
   - "Exit Test Mode" button appears

4. **DevTools verification:**
   ```javascript
   // In Console:
   lab.app.store.deref()
   // Should show:
   // {:test-mode? true, :detected-notes [0 4 7], :detected-midis [60 64 67], ...}
   ```

### Test 2: D Minor Chord
1. Click "d-minor" button
2. **Expected:**
   - Spectrum updates
   - Labels: D, F, A
   - Chord: "D m"
   - Console: "Loading test data: d-minor" + bin count

### Test 3: Exit Test Mode
1. Click "Exit Test Mode" button
2. **Expected:**
   - Button disappears
   - Spectrum clears
   - Chord display clears
   - Animation stops

## Button Click Test (DevTools Event Simulation)

### In DevTools Console:
```javascript
// Simulate button click
const btn = document.querySelector('button');
btn.click();

// Check if event handler fired
lab.app.store.deref().test_mode_QMARK_
// Should return true if test mode active
```

## Canvas Rendering Test

### Check canvas element:
```javascript
const canvas = document.getElementById('pitch-canvas');
console.log('Canvas size:', canvas.width, 'x', canvas.height);
console.log('Canvas context:', canvas.getContext('2d'));

// Should show:
// Canvas size: 900 x 400
// Canvas context: CanvasRenderingContext2D {...}
```

### Verify rendering happened:
```javascript
const ctx = document.getElementById('pitch-canvas').getContext('2d');
const imageData = ctx.getImageData(0, 0, 900, 400);
const hasPixels = Array.from(imageData.data).some(v => v > 0);
console.log('Canvas has drawn pixels:', hasPixels);
// Should be true after clicking test button
```

## Direct Function Tests

### Test pitch detection:
```javascript
const testData = audio.test_data.make_test_data(new cljs.core.Keyword(null, 'c-major', 'c-major'));
const result = audio.pitch.find_polyphonic(testData,
  new cljs.core.Keyword(null, 'legit-energy', 'legit-energy'), 0.25);

console.log('Detected notes:', cljs.core.pr_str(result.get(new cljs.core.Keyword(null, 'legit-notes', 'legit-notes'))));
// Should show: [0 4 7]
```

### Test chord recognition:
```javascript
const chord = audio.pitch.detect_chord(cljs.core.vector(0, 4, 7));
console.log('Chord:', cljs.core.first(chord), cljs.core.second(chord));
// Should show: 0 "Major"
```

### Test OKLCH colors:
```javascript
const color = audio.utils.midi__GT_oklch(60);
console.log('C4 color:', color);
// Should show: "oklch(0.65 0.15 60 / 1.0)" or similar
```

## Known Issues / Expected Warnings

- ✓ Replicant nested render warnings: FIXED (removed add-watch)
- ✓ Forward reference warnings: FIXED (added declares)
- ✓ Animation loop not starting: FIXED (start loop on test-data load)

## Troubleshooting

### If buttons don't work:
1. Check Console for errors
2. Verify event handler attached:
   ```javascript
   document.querySelector('button').onclick
   // Should show function or null
   ```

### If canvas is black:
1. Check canvas initialized:
   ```javascript
   lab.app.canvas_state.deref()
   // Should show: {:canvas #<canvas>, :ctx #<CanvasRenderingContext2D>}
   ```

2. Check test data loaded:
   ```javascript
   lab.app.store.deref().test_data
   // Should show vector of 500 bin objects
   ```

3. Manually trigger render:
   ```javascript
   lab.app.re_render_BANG_()
   ```

### If nothing renders:
1. Check root element exists:
   ```javascript
   document.getElementById('root')
   // Should show div
   ```

2. Force re-render:
   ```javascript
   lab.app.main()
   ```

## Success Criteria

✅ All 6 test buttons work
✅ Canvas shows colorful spectrum
✅ Note labels appear (C, E, G for C major)
✅ Chord name displays correctly
✅ No console errors
✅ Console debug logs appear
✅ Exit test mode works
