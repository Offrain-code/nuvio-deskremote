import { NavAction, KeyName, ScreenName, StateSnapshot } from './protocol';

/**
 * Nuvio screen state machine (Home / Detail / Player / Search).
 *
 * Two sources of truth:
 * 1. App commands (immediate screen prediction — `source: "command"`, `confirmed: false`).
 * 2. nuvio.log tail (instant confirmation — `source: "log"`, `confirmed: true`).
 */

export interface StateEvent {
  snapshot: StateSnapshot;
  source: 'command' | 'log';
  /** Whether this transition is confirming a previously predicted state. */
  confirming: boolean;
  detail?: string;
}

type Predict = { screen: ScreenName; confirmed: false; detail?: string } | null;

export class ScreenStateMachine {
  private screen: ScreenName = 'home';
  private confirmed = false;
  private returnScreen: ScreenName = 'detail';
  private searchReturn: ScreenName = 'home';
  private listeners: ((ev: StateEvent) => void)[] = [];

  snapshot(): StateSnapshot {
    return { screen: this.screen, playerActive: this.screen === 'player', confirmed: this.confirmed };
  }

  onChange(cb: (ev: StateEvent) => void): void {
    this.listeners.push(cb);
  }

  private set(screen: ScreenName, confirmed: boolean, source: 'command' | 'log', detail?: string): void {
    if (screen === this.screen && confirmed === this.confirmed) return;
    const confirming = source === 'log' && screen === this.screen;
    const prevScreen = this.screen;
    this.screen = screen;
    this.confirmed = confirmed;
    if (screen === 'player') this.returnScreen = prevScreen === 'detail' || prevScreen === 'home' ? prevScreen : 'detail';
    if (screen === 'search') this.searchReturn = prevScreen === 'detail' ? 'detail' : 'home';
    for (const cb of this.listeners) cb({ snapshot: this.snapshot(), source, confirming, detail });
  }

  /** Prediction driven by an app navigation command. */
  applyNav(action: NavAction): { injected: true; changed: boolean; detail?: string } {
    const cur = this.screen;
    const predict = this.predictNav(cur, action);
    if (predict) this.set(predict.screen, false, 'command', predict.detail ?? undefined);
    return { injected: true, changed: !!predict, detail: predict?.detail };
  }

  private predictNav(cur: ScreenName, action: NavAction): Predict {
    const go = (screen: ScreenName, detail: string): Predict => ({ screen, confirmed: false, detail });
    if (action === 'home') return go('home', 'goto home');
    if (action === 'search' && cur !== 'player') return go('search', 'open search');
    if (action === 'library' || action === 'discover' || action === 'calendar' || action === 'live') {
      if (cur !== 'player') return go('home', `goto ${action}`);
    }
    switch (cur) {
      case 'home':
      case 'unknown':
        if (action === 'ok') return go('detail', 'enter detail');
        if (action === 'search') return go('search', 'open search');
        return null;
      case 'detail':
        if (action === 'back') return go('home', 'leave detail');
        if (action === 'search') return go('search', 'open search');
        return null;
      case 'player':
        if (action === 'back') return go(this.returnScreen, 'exit player (awaiting log exit marker)');
        return null;
      case 'search':
        if (action === 'back') return go(this.searchReturn, 'close search');
        if (action === 'ok') return go('detail', 'open result');
        return null;
      default:
        return null;
    }
  }

  /** Raw key press may change screen state when it matches a global navigation shortcut. */
  applyRawKey(key: KeyName): { changed: boolean } {
    let changed = false;
    switch (key) {
      case 'Escape':
      case 'Backspace':
        if (this.screen === 'player') this.set(this.returnScreen, false, 'command', 'exit player (raw key)');
        else if (this.screen === 'detail') this.set('home', false, 'command', 'leave detail');
        else if (this.screen === 'search') this.set(this.searchReturn, false, 'command', 'close search');
        changed = true;
        break;
      case 'H':
        this.set('home', false, 'command', 'goto home');
        changed = true;
        break;
      case 'S':
        if (this.screen === 'player') break; // S = cycle subtitles while in player
        this.set('search', false, 'command', 'open search');
        changed = true;
        break;
      default:
        break;
    }
    return { changed };
  }

  /** Confirmation sourced from the log tail. */
  applyLogEnter(detail: string): void {
    this.set('player', true, 'log', detail);
  }

  applyLogExit(detail: string): void {
    if (this.screen !== 'player') return;
    this.set(this.returnScreen, true, 'log', detail);
  }

  applyLogScreen(screen: ScreenName, detail?: string): void {
    this.set(screen, true, 'log', detail);
  }

  /** Allows a client to force a state transition (e.g., manual resync from the app). */
  forceState(screen: ScreenName): void {
    this.set(screen, false, 'command', 'client force');
  }
}
