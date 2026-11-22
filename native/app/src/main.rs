//! Prism desktop app: finds games, injects Prism (by hand or automatically into Lunar 1.8.9),
//! lists plugins and shows the log.
#![cfg_attr(windows, windows_subsystem = "windows")]

mod files;

use eframe::egui::{self, Color32, RichText};
use prism_inject::GameProcess;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::mpsc;
use std::time::{Duration, Instant};

const ACCENT: Color32 = Color32::from_rgb(0xb3, 0x7d, 0xff);
const GOOD: Color32 = Color32::from_rgb(0x6d, 0xd6, 0x8c);
const BAD: Color32 = Color32::from_rgb(0xff, 0x6b, 0x6b);
const MUTED: Color32 = Color32::from_rgb(0x9a, 0x94, 0xa8);
/// Wait after a Lunar window appears before auto-injecting (the agent also waits for the game).
const AUTO_DELAY: Duration = Duration::from_secs(3);

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Prism")
            .with_inner_size([520.0, 600.0])
            .with_min_inner_size([420.0, 420.0]),
        ..Default::default()
    };
    eframe::run_native("Prism", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}

enum Job {
    Injected(u32, Result<(), String>),
}

struct App {
    home: PathBuf,
    install: Result<files::Install, String>,
    settings: files::Settings,
    games: Vec<GameProcess>,
    plugins: Vec<files::PluginFile>,
    log: String,
    status: Option<(String, bool)>,
    last_refresh: Instant,
    /// pid -> when its window was first seen (for the auto-inject delay)
    first_seen: HashMap<u32, Instant>,
    /// pids we already tried to auto-inject (never retried automatically)
    auto_tried: HashSet<u32>,
    busy: HashSet<u32>,
    jobs_tx: mpsc::Sender<Job>,
    jobs_rx: mpsc::Receiver<Job>,
}

impl App {
    fn new(cc: &eframe::CreationContext) -> Self {
        style(&cc.egui_ctx);
        let home = files::home();
        let install = files::install(&home);
        let settings = files::load_settings(&home);
        let (jobs_tx, jobs_rx) = mpsc::channel();
        let mut app = App {
            home,
            install,
            settings,
            games: Vec::new(),
            plugins: Vec::new(),
            log: String::new(),
            status: None,
            last_refresh: Instant::now() - Duration::from_secs(60),
            first_seen: HashMap::new(),
            auto_tried: HashSet::new(),
            busy: HashSet::new(),
            jobs_tx,
            jobs_rx,
        };
        app.refresh();
        app
    }

    fn refresh(&mut self) {
        self.games = prism_inject::list_games();
        let now = Instant::now();
        let live: HashSet<u32> = self.games.iter().map(|g| g.pid).collect();
        self.first_seen.retain(|pid, _| live.contains(pid));
        for g in &self.games {
            self.first_seen.entry(g.pid).or_insert(now);
        }
        self.plugins = files::plugins(&self.home);
        self.log = files::log_tail(&self.home, 200);
        self.last_refresh = now;
    }

    fn inject(&mut self, pid: u32) {
        let Ok(install) = &self.install else { return };
        let dll = install.agent.clone();
        let tx = self.jobs_tx.clone();
        self.busy.insert(pid);
        std::thread::spawn(move || {
            let _ = tx.send(Job::Injected(pid, prism_inject::inject(pid, &dll)));
        });
    }

    fn unload(&mut self, pid: u32) {
        match prism_inject::request_unload(&self.home.join("bin"), pid) {
            Ok(()) => self.status = Some(("Unloading…".into(), true)),
            Err(e) => self.status = Some((format!("Unload failed: {e}"), false)),
        }
    }

    fn auto_inject(&mut self) {
        if !self.settings.auto_inject || self.install.is_err() {
            return;
        }
        let due: Vec<u32> = self
            .games
            .iter()
            .filter(|g| g.is_lunar_189() && !g.injected && !self.auto_tried.contains(&g.pid))
            .filter(|g| {
                self.first_seen
                    .get(&g.pid)
                    .is_some_and(|t| t.elapsed() >= AUTO_DELAY)
            })
            .map(|g| g.pid)
            .collect();
        for pid in due {
            self.auto_tried.insert(pid);
            self.inject(pid);
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        while let Ok(job) = self.jobs_rx.try_recv() {
            match job {
                Job::Injected(pid, r) => {
                    self.busy.remove(&pid);
                    self.status = Some(match r {
                        Ok(()) => ("Injected".into(), true),
                        Err(e) => (format!("Inject failed: {e}"), false),
                    });
                    self.refresh();
                }
            }
        }
        if self.last_refresh.elapsed() >= Duration::from_secs(2) {
            self.refresh();
            self.auto_inject();
        }
        ctx.request_repaint_after(Duration::from_millis(500));

        egui::TopBottomPanel::top("header").show(ctx, |ui| {
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                ui.label(RichText::new("Prism").size(22.0).strong().color(ACCENT));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let v = match &self.install {
                        Ok(i) => format!("v{} · {}", env!("CARGO_PKG_VERSION"), &i.build[..6]),
                        Err(_) => format!("v{}", env!("CARGO_PKG_VERSION")),
                    };
                    ui.label(RichText::new(v).color(MUTED));
                });
            });
            ui.add_space(6.0);
        });

        egui::TopBottomPanel::bottom("status").show(ctx, |ui| {
            ui.add_space(4.0);
            match (&self.install, &self.status) {
                (Err(e), _) => {
                    ui.label(RichText::new(e).color(BAD));
                }
                (_, Some((msg, ok))) => {
                    ui.label(RichText::new(msg).color(if *ok { GOOD } else { BAD }));
                }
                _ => {
                    ui.label(RichText::new(self.home.display().to_string()).color(MUTED));
                }
            }
            ui.add_space(4.0);
        });

        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                section(ui, "Games");
                if self.games.is_empty() {
                    ui.label(RichText::new("No game running").color(MUTED));
                }
                let mut action: Option<(u32, bool)> = None;
                for g in &self.games {
                    card(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.vertical(|ui| {
                                ui.label(RichText::new(&g.title).strong());
                                let (txt, col) = if g.injected {
                                    ("Injected", GOOD)
                                } else {
                                    ("Not injected", MUTED)
                                };
                                ui.label(RichText::new(txt).color(col).small());
                            });
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    if self.busy.contains(&g.pid) {
                                        ui.spinner();
                                    } else if g.injected {
                                        if ui.button("Unload").clicked() {
                                            action = Some((g.pid, false));
                                        }
                                    } else if ui
                                        .add_enabled(
                                            self.install.is_ok(),
                                            egui::Button::new(RichText::new("Inject").strong())
                                                .fill(ACCENT.linear_multiply(0.35)),
                                        )
                                        .clicked()
                                    {
                                        action = Some((g.pid, true));
                                    }
                                },
                            );
                        });
                    });
                }
                match action {
                    Some((pid, true)) => self.inject(pid),
                    Some((pid, false)) => self.unload(pid),
                    None => {}
                }
                let before = self.settings.clone();
                ui.checkbox(&mut self.settings.auto_inject, "Auto-inject Lunar 1.8.9");
                if self.settings != before {
                    files::save_settings(&self.home, &self.settings);
                }

                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    section(ui, "Plugins");
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.small_button("Open folder").clicked() {
                            open_folder(&self.home.join("plugins"));
                        }
                    });
                });
                if self.plugins.is_empty() {
                    ui.label(
                        RichText::new("No plugins. Drop .jar files in the plugins folder.")
                            .color(MUTED),
                    );
                }
                for p in &self.plugins {
                    card(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new(&p.name).strong())
                                .on_hover_text(&p.file);
                            ui.label(RichText::new(&p.version).color(MUTED));
                            if let Some(e) = &p.error {
                                ui.with_layout(
                                    egui::Layout::right_to_left(egui::Align::Center),
                                    |ui| {
                                        ui.label(RichText::new(e).color(BAD).small());
                                    },
                                );
                            }
                        });
                    });
                }

                ui.add_space(10.0);
                egui::CollapsingHeader::new(RichText::new("Log").strong())
                    .default_open(false)
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            if ui.small_button("Open logs folder").clicked() {
                                open_folder(&self.home.join("logs"));
                            }
                        });
                        egui::ScrollArea::vertical()
                            .max_height(220.0)
                            .stick_to_bottom(true)
                            .show(ui, |ui| {
                                ui.add(
                                    egui::Label::new(
                                        RichText::new(if self.log.is_empty() {
                                            "(empty)"
                                        } else {
                                            &self.log
                                        })
                                        .monospace()
                                        .small(),
                                    )
                                    .wrap(),
                                );
                            });
                    });
            });
        });
    }
}

fn section(ui: &mut egui::Ui, title: &str) {
    ui.label(RichText::new(title).size(15.0).strong());
    ui.add_space(2.0);
}

fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(Color32::from_rgb(0x1d, 0x1a, 0x26))
        .rounding(8.0)
        .inner_margin(egui::Margin::symmetric(10.0, 8.0))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            add(ui)
        });
    ui.add_space(4.0);
}

fn open_folder(path: &std::path::Path) {
    let _ = std::fs::create_dir_all(path);
    #[cfg(windows)]
    let _ = std::process::Command::new("explorer").arg(path).spawn();
    #[cfg(not(windows))]
    let _ = std::process::Command::new("xdg-open").arg(path).spawn();
}

fn style(ctx: &egui::Context) {
    let mut v = egui::Visuals::dark();
    v.panel_fill = Color32::from_rgb(0x13, 0x11, 0x1a);
    v.window_fill = v.panel_fill;
    v.selection.bg_fill = ACCENT.linear_multiply(0.5);
    v.hyperlink_color = ACCENT;
    v.widgets.inactive.rounding = 6.0.into();
    v.widgets.hovered.rounding = 6.0.into();
    v.widgets.active.rounding = 6.0.into();
    ctx.set_visuals(v);
    let mut s = (*ctx.style()).clone();
    s.spacing.item_spacing = egui::vec2(8.0, 6.0);
    s.spacing.button_padding = egui::vec2(12.0, 5.0);
    ctx.set_style(s);
}
