# Station Alarm

Android app that notifies you before your station — Mumbai Western line and Metro 1, 2A, 7, and long-distance trains anywhere in India (by train number or any station). Works with no network: station data and timetables are stored in the app.

- Download: the latest `StationAlarm.apk` from Releases (built automatically on every push to `main`).
- Data: train timetables and station locations from datameet Indian Railways data (CC0, 2016). Metro 2A/7 station locations © OpenStreetMap contributors (ODbL). Some Western line and Metro Line 1 station locations are estimates.

## Live train status and PNR (v0.8)

- **Track a train**: enter a train number, read NTES "Spot Your Train" inside the app, and see where the train is, how late it is, the next stop with an expected time, and the platform for each station. Works without riding the train.
- **On a trip**: refresh the train's NTES status from the trip screen. The alert before your stop now says the arrival time, the platform and any delay.
- **PNR**: every re-check is compared with the last one. Changes (WL to RAC/CNF, chart prepared) are highlighted, each passenger shows coach and berth, and a reminder to check again comes about 4 hours before departure while any passenger is unconfirmed. The next ticket also shows on the home-screen widget.
- NTES and the PNR page still need you to open them in the app (and type the PNR captcha); nothing is read in the background.

Off-device parser tests: see `android/tests/ParserTests.java`.
