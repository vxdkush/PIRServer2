package com.hongkong.game.casino;

import java.io.IOException;
import java.security.Key;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Vector;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.log4j.Logger;

import com.atlantis.util.Rng;
import com.atlantis.util.Utils;
import com.hongkong.common.db.DBEAP;
import com.hongkong.common.db.DBException;
import com.hongkong.common.db.GameRunSession;
import com.hongkong.game.Game;
import com.hongkong.game.GameType;
import com.hongkong.game.Moves;
import com.hongkong.game.Player;
import com.hongkong.game.Player.Presence;
import com.hongkong.game.util.Card;
import com.hongkong.server.GamePlayer;
import com.hongkong.server.GameProcessor;
import com.atlantis.util.Base64;

public class RummyServer extends CasinoGame {
	static Logger _cat = Logger.getLogger(RummyServer.class.getName());
	String _name;
	double _minBet;
	double _maxBet;
	double totalBet = 0, totalWin = 0, totalGames = 0;
	String moveDetails = "";

	public static final int MAX_PLAYERS = 6;
	public static final int move_RUMMY_INVALID = 0;
	public static final int move_RUMMY_FOLD = 1;
	public static final int move_RUMMY_NEWCARD = 2;
	public static final int move_RUMMY_DISCARD = 4;
	public static final int move_RUMMY_DECLARE = 8;
	public static final int move_RUMMY_RUMMYCUT = 16;
	public static final int move_RUMMY_LEFT = 32;
	public static final int move_RUMMY_JOINED = 64;
	public static final int move_RUMMY_DECLARE_LOSER = 128;
	public static final int move_RUMMY_CHAT = 256;
	
	public static final int move_RUMMY_SITOUT = 512;
	public static final int move_RUMMY_SITIN = 1024;
	
	public int globalPenal = 0;
	public boolean globalFirstLife = false, globalSecondLife = false;
	//for bots
	public static final int move_RUMMY_BOTREGISTER = 2048;

	public static final int status_NONE = 1;
	public static final int status_ACTIVE = 2;
	public static final int status_RUMMYFIRST = 4;
	public static final int status_REMOVED = 8;
	public static final int status_DEALER = 16;
	public static final int status_WINNER = 32;
	public static final int status_SITTINGOUT = 64;
	public static final int status_FOLDED = 128;
	public static final int status_LEFT = 256;
	public static final int status_BROKE = 512;
	public static final int status_JOINED = 1024;

	static final int USED_SEEN_ONCE = 1;
	static final int USED_SEEN_TWICE = 2;
	static final int USED_SEEN_THRICE = 4;
	static final int USED_PURE_RUN = 8;
	static final int USED_IMPURE_RUN = 16;
	static final int USED_IN_SET = 32;
	static final int USED_AS_JOKER = 64;
	static final int USED_AS_TEMP_JOKER = 128;

	//adding equal number of private tables
	public static final int MAX_TABLES = 20;
	public static final int MAX_OPEN_TABLES = 10;

	RummyTable[] _tables = null;

	boolean _keepServicing = false;

	private LinkedList[] _msgQ = new LinkedList[MAX_TABLES];

	public void add(int index, Presence cmdStr) {
		(_msgQ[index]).add(cmdStr);
	}

	public Presence fetch(int index) {
		return (Presence) (_msgQ[index].removeFirst());
	}

	public RummyServer(String name, double minB, double maxB, GameType type,
			int gid) {
		_gid = gid;
		_name = name;
		_minBet = minB;
		_maxBet = maxB;
		_type = type;
		//_cat.debug(this);
		_keepServicing = true;
		// one linkedlist per thread
		for (int i = 0; i < MAX_TABLES; i++) {
			_msgQ[i] = new LinkedList();
		}
		_tables = new RummyTable[MAX_TABLES];
		for (int i = 0; i < MAX_TABLES; i++) {
			_tables[i] = new RummyTable();
			_tables[i].validTable = true;
			_tables[i].POINTSPERRUPEE = (i + 1) * 0.2;
//			if (_tables[i].POINTSPERRUPEE > 1.3
//					&& _tables[i].POINTSPERRUPEE < 1.6)
//				_tables[i].POINTSPERRUPEE = 1.5;
//			else if (_tables[i].POINTSPERRUPEE == 1.6)
//				_tables[i].POINTSPERRUPEE = 2.0;
//			else if (_tables[i].POINTSPERRUPEE == 1.8)
//				_tables[i].POINTSPERRUPEE = 5.0;
//			else if (_tables[i].POINTSPERRUPEE == 2.0)
//				_tables[i].POINTSPERRUPEE = 10;
			
			if (i >= MAX_OPEN_TABLES){
				//private table. it is fixed.
				_tables[i].POINTSPERRUPEE = 1.0;
			}
			
			int temp = (int)(_tables[i].POINTSPERRUPEE * 100);
			_tables[i].POINTSPERRUPEE = (((double)temp) / 100);

			Thread t = new Thread(_tables[i]);
			t.setName("Rummy-Table-" + i);
			t.setPriority(Thread.NORM_PRIORITY);
			_tables[i].setIndex(i);
			t.start();

			_cat.debug("starting indian thread : " + i);
		}
	}

	public StringBuffer gameDetail() {
		StringBuffer sb;
		sb = new StringBuffer("RummyServer=").append(_name).append(",min-bet=")
				.append(_minBet).append(",max-bet=").append(_maxBet)
				.append(",RummyTables=");
		// now add details of all tables
		for (int i = 0; i < MAX_OPEN_TABLES; i++) {
			if (_tables[i].validTable) {
				double dbl = _tables[i].POINTSPERRUPEE;
				// int intdbl = ((int) dbl / 100);
				sb.append(dbl);
				sb.append("'");
				sb.append(_tables[i]._tid).append(
						"'" + _tables[i].getMaxPlayers());
				sb.append("'" + _tables[i].getCurrentPlayersCount());
				String details = _tables[i].getCurrentPlayersDetail();
				sb.append("'" + details);

				if (i != MAX_OPEN_TABLES - 1)
					sb.append(":");
			}
		}
		
		sb.append(",RummyPvtTables=");
		//add details of private tables
		for (int i = MAX_OPEN_TABLES; i < MAX_TABLES; i++) {
			if (_tables[i].validTable) {
				double dbl = _tables[i].POINTSPERRUPEE;
				// int intdbl = ((int) dbl / 100);
				sb.append(dbl);
				sb.append("'");
				sb.append(_tables[i]._tid).append("'" + _tables[i].getMaxPlayers());
				sb.append("'" + _tables[i].getCurrentPlayersCount());
				String details = _tables[i].getCurrentPlayersDetail();
				sb.append("'" + details);

				if (i != MAX_TABLES - 1)
					sb.append(":");
			}
		}
		
		return sb;
	}

	class RummyProfile {

		RummyProfile() {
			// initialize other variables if there is a need
			rummyStatus = -1;
		}

		String name = "";

		public void setName(String nam) {
			name = nam;
		}

		public String getName() {
			return name;
		}
		
		double betAmt = 0;
		public void setBetAmt(double amt){
			betAmt = amt;
		}
		
		public double getBetAmt(){
			return betAmt;
		}

		double startWorth = 0;

		public void setGameStartWorth(double worth) {
			startWorth = worth;
		}

		public double getGameStartWorth() {
			return startWorth;
		}

		boolean reqForChipsReload = false;

		public void setReloadReq(boolean flag) {
			reqForChipsReload = flag;
		}

		public boolean getReloadReq() {
			return reqForChipsReload;
		}

		int rummyMove;

		public int getRUMMYMoveId() {
			return rummyMove;
		}

		public void setRUMMYMoveId(int mvId) {
			rummyMove = mvId;
		}

		String rummyMovesStr;

		public String getRUMMYMovesStr() {
			return rummyMovesStr;
		}

		public void setRUMMYMovesStr(String moves) {
			rummyMovesStr += moves;
		}

		public void clearRUMMYMovesStr() {
			rummyMovesStr = "";
		}

		int rummyStatus;

		public int getRUMMYStatus() {
			return rummyStatus;
		}

		public void setRUMMYStatus(int status) {
			rummyStatus = status;
		}

		public long rummyLeftTableTime = -1;

		public long rummyLastMoveTime = -1;

		int rummyPointsToPay;

		public int getRUMMYPoints() {
			return rummyPointsToPay;
		}

		public void setRUMMYPoints(int val) {
			rummyPointsToPay = val;
		}

		double rummyWinAmt;

		public double getRUMMYWinAmt() {
			return rummyWinAmt;
		}

		public void setRUMMYWinAmt(double winamt) {
			rummyWinAmt = winamt;
		}

		int _pos = -1;

		public int pos() { // _cat.debug("HI");
			return _pos;
		}

		public void setPos(int pos) { // _cat.debug("HI");
			_pos = pos;
		}

		public int _tid;

		Presence pr = null;

		public void setPresence(Presence p) {
			pr = p;
		}

		public Presence getPresence() {
			return pr;
		}
		
		boolean usingTimeBank = false;
		public void setUsingTimeBank(boolean ffla){
			usingTimeBank = ffla;
			if (ffla){
				decrTimeBank();
				
				setTimeBankExpTime();
			}
			_cat.debug("setusingtimebank : " + timeBankValue);
		}
		public boolean getUsingTimeBank(){
			return usingTimeBank;
		}
		
		public boolean isEligibleTimeBank(){
			if (usingTimeBank)
				return false;
			
			return timeBankValue > -1;
		}
		
		//timebankvalue goes in increments of 10 seconds for this game.
		int timeBankValue = 3;
		int timeBankValueUsed = -1;
		public void incrTimeBank(){
			if (timeBankValue >= 3)
				timeBankValue = 3;
			else
				timeBankValue++;
			
			setUsingTimeBank(false);
			timeBankValueUsed = -1;
		}
		
		public void decrTimeBank(){
			timeBankValueUsed = timeBankValue;
			
			timeBankValue--;
			if (timeBankValue < 0)
				timeBankValue = -1;
		}
		
		public void setTimeBank(long timeUsedBank){
			int val = 0;
			while (val * 10000 < timeUsedBank)
				val++;
			
			timeBankValue = val;
			timeBankValueUsed = -1;
			_cat.debug("new timebank value : " + timeBankValue);
		}
		
		public int getTimeBank(){
			return timeBankValueUsed;
		}
		
		long timeBankExpTime = 0;
		public void setTimeBankExpTime(){
			//every usage of timebank is for 10 seconds for each timebankvalue
			_cat.debug("settimebankexp : " + timeBankValueUsed);
			timeBankExpTime = System.currentTimeMillis() + 10000 * (timeBankValueUsed);
		}
		
		public long getTimeBankExpTime(){
			return timeBankExpTime;
		}

		int _state = -1;
		final static int RUMMYPLAYER = 8;

		public boolean isRummyPlayer() { // _cat.debug("HI");
			return (_state & RUMMYPLAYER) > 0;
		}

		public void setRummyPlayer() { // _cat.debug("HI");
			_state |= RUMMYPLAYER;
		}

		public void unsetRummyPlayer() { // _cat.debug("HI");
			_state &= ~RUMMYPLAYER;
		}

		public Card[] _allotedCards = null;
		public boolean _firstMoveDone = false;

		public String cardsStrFromClient = "";

		public boolean _markCheckingCards = false;

		public int fixPosCard = -1;
	}

	class RummyTable implements Runnable {
		volatile boolean _gameOngoing = false;
		double _rake;
		static final double RAKE_PERCENTAGE = 5;
		double POINTSPERRUPEE = 0.5;
		volatile RummyProfile[] _players = new RummyProfile[MAX_PLAYERS];
		// all the players who paid ante or who opted out of ante only - for
		// entries in grs table
		int _nextMovesAllowed;// to remember the moves that are allowed
		volatile int _nextMovePlayer = -1;
		volatile int _dealer;// to mark the very first player
		volatile int _rummyPlayer;// first one to make a move.
		int _winnerPos = -1;// outer or inner - where did the winning card fall?

		boolean fixingDealerOngoing = false;
//		boolean fixingDealerNextHand = true;
//		int countHandsAfterDealerFix = 0;
		
		int countHandsBotOnly = 0;

		String _winnerString = "";
		
		String _amtWonString = "";
		
		double dbwinamt = 0;
		
		int dbpotcontendors = 0;

		String newCardAdded = "";

		String _cardDealingOrder = "";

		// the card for which the players wage war
		Card _rummyCardJoker, _discardCard, _prevDiscardCard;

		String finalResultRuns = "", finalResultRuns3 = "",
				finalResultRuns1 = "", finalResultRuns2 = "";

		int tanalaCount = 0;
		int idPlayerChecked = -1;

		int idPlayerValidDeclared = -1;

		int countPlayerResponseDeclareLoser = 0;

		boolean validTable = false;

		BitSet _deck = new BitSet(); // this is for fresh pile
		Card[] _discardDeck = new Card[65]; // when ever a card is discarded, we
											// add it here. when one is removed,
											// prev card comes to fore.
		// when the fresh pile runs out, we unset the bits in _desk that are set
		// in _discardDeck and empty out _discardDeck
		int _indexDiscardDeck;

		int totalJokersAlreadyDealt = 0;
		boolean printedJokerDealt = false;

		int NUM_DECKS = 3;
		int MAX_CARDS = 52 * NUM_DECKS - 1;

		int _lastMove;
		int _lastMovePos = -1;
		String _lastMoveString = "";

		Vector _observers = new Vector();

		long rummygrid;
		Calendar _gameStartTime;

		long _nextMoveExpTime;
		int _tid = -1;
		
		int counterGameNotStarted = 0;

		volatile long lastRespTime = 0;

		String[] indicesCardsInRun = new String[13]; // it will have entries
														// stored as
														// count'7`8`9|2`3`4 -
														// will be helpful in
														// computing penalty
		String globalJokerTracker = "";
		BitSet bitsetJoker, bitsetPlyrCards;
		int[] removedCardsDupli = new int[6];// expecting at most 6 pairs
		int indexDupli = 0;
		int[] playerStatus = null;

		int despoRun2 = 0, despoRun = 0;
		boolean sayNotoFirstOrLastJoInPureRun = false;
		boolean sayNotoAllJokersInPureRun = false;
		
		String chatMessage = "";
		int chatOn = 0;
		
		boolean toSendMsg = false;
		
		//for sitting out players
		ArrayList<Integer> sitoutPlayers = new ArrayList<Integer>();
		ArrayList<Integer> sitinPlayers = new ArrayList<Integer>();

		public RummyTable() {
			for (int i = 0; i < MAX_PLAYERS; i++) {
				_players[i] = null;
			}
			_nextMoveExpTime = System.currentTimeMillis();
		}

		public void setIndex(int index) {
			_tid = index;
		}

		public RummyProfile findProfile(Presence p) {
			for (int k = 0; k < MAX_PLAYERS; k++) {
				if (_players[k] != null
						&& _players[k].getName().compareToIgnoreCase(p.name()) == 0) {
					return _players[k];
				}
			}
			return null;
		}

		public RummyProfile findProfile(String name) {
			for (int k = 0; k < MAX_PLAYERS; k++) {
				if (_players[k] != null
						&& _players[k].getName().compareToIgnoreCase(name) == 0) {
					return _players[k];
				}
			}
			return null;
		}

		public int addObserver(RummyProfile p) {
			// first check if he is already in this list, if so don't do
			// anything
			int i = 0;
			for (i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				if (p.getName().compareToIgnoreCase(pro.getName()) == 0)
					return i;
			}
			_observers.add(p);
			_cat.debug("adding observor : " + p.getName()
					+ " on table : " + _tid + " on pos : " + i);
			return _observers.size();
		}

		public void removeObserver(RummyProfile p) {
			_observers.remove(p);
		}

		public int findObsPos(Presence p) {
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				_cat.debug("from list : " + pro.getName());
				if (p.name().compareToIgnoreCase(pro.getName()) == 0)
					return i;
			}
			return -1;
		}

		private RummyProfile findObservor(int pos) {
			if (pos != -1)
				return (RummyProfile) _observers.get(pos);
			else
				return null;
		}
		
		private RummyProfile findObservor(String name) {
			if (!name.isEmpty()) {
				for (int i = 0; i < _observers.size(); i++){
					RummyProfile rp = (RummyProfile) _observers.get(i);
					if (rp.name.compareToIgnoreCase(name) == 0)
						return rp;
				}
			}
			return null;
		}

		public void removeFromTable(int index) {
			// for (int k = 0; k < MAX_PLAYERS; k++) {
			// if (_players[k] != null
			// && _players[k].getName().compareToIgnoreCase(
			// p.getName()) == 0) {
			// // found the offending player
			// _players[k].rummyLeftTableTime = -1;
			// _players[k] = null;
			// _cat.debug("kicked out .... from table : " + _tid);
			// break;
			// }
			// }

			_players[index].rummyLeftTableTime = System.currentTimeMillis();
			// _players[index] = null; //can't make it null
			_players[index].setRUMMYStatus(status_LEFT);
			_players[index].setRUMMYMoveId(move_RUMMY_LEFT);
		}
		
		public void handleSitoutSitin(){

			//for supporting sitout and sitin. 
			//sitout means next game he wont join
			//sitin means next game he will be joining
			for (int m = 0; m < sitoutPlayers.size(); m++){
				int pos = sitoutPlayers.get(m);
				if (_players[pos] != null && !isRemoved(_players[pos]) && !isLeft(_players[pos]) ){
					_cat.debug("active player wants to sit out. ok. " + pos);
					_players[pos].setRUMMYStatus(status_SITTINGOUT);
				}
			}
			
			sitoutPlayers.clear();
			
			for (int m = 0; m < sitinPlayers.size(); m++){
				int pos = sitinPlayers.get(m);
				if (_players[pos] != null && isSittingOut(_players[pos])){
					_cat.debug("sitting out player wants to rejoin. ok.");
					_players[pos].setRUMMYStatus(status_ACTIVE);
				}
			}
			
			sitinPlayers.clear();
		
		}

		public void resetTable() {
			//find a friend feature - clear out here
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					_players[m].getPresence().player().setActiveGame(-1);
				}
			}
			
			if(!_gameOngoing){
				handleSitoutSitin();
			}
			
			// first cull the players - this will leave some open seats
			// _cat.debug("from reset table");
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					double amt = 0;
					if (_players[m].getPresence().isBot)
						amt = _players[m].getGameStartWorth();
					else
						amt = _players[m].getPresence().getAmtAtTable();
					
					if (isRemoved(_players[m])
							|| isLeft(_players[m])
							||(amt < 80 * POINTSPERRUPEE)
					// ||!_players[m].getRUMMYBetApplied() //this condition is
					// to throw out the players who just sit on table
					) {
						sendMessageKickedOut(m, 0);
						removeFromTable(m);
						_players[m] = null;
					} else {
						_players[m].clearRUMMYMovesStr();
						_players[m].setReloadReq(false);
						_players[m]._allotedCards = null;
						_players[m]._firstMoveDone = false;
						if (!isSittingOut(_players[m]))
							_players[m].setRUMMYStatus(status_ACTIVE);
						_players[m].rummyLastMoveTime = System
								.currentTimeMillis();
						_players[m].cardsStrFromClient = "";
						_players[m].getPresence().resetRoundBet();
					}
				}
			}
			
			//now check on the bots
			int countActive = getCntStatusActivePlayers();
//			_cat.debug("SERVER reset table ACTIVE PLAYERS : " + countActive);
			int countBot = 0;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					if (_players[m].getPresence().isBot) {
						countBot++;
					}
				}
			}
			
			if (countBot > 0) {
				if (countBot == countActive){
					_cat.debug("ONLY THE BOTS PLAYING. USE 10 COUNT OF GAMES TO GET THEM OUT... " + countHandsBotOnly);
					if (countHandsBotOnly >= 10){
						for (int m = 0; m < MAX_PLAYERS; m++) {
							if (_players[m] != null) {
								if (_players[m].getPresence().isBot) {
									removeFromTable(m);
									_players[m] = null;
								}
							}
						}
						countHandsBotOnly = 0;
					}
				}
				else {
					_cat.debug("BOTS ARE PLAYING WITH SOMEONE. GET OUT AFTER 10 HANDS");
					if (countHandsBotOnly >= 10){
						for (int m = 0; m < MAX_PLAYERS; m++) {
							if (_players[m] != null) {
								if (_players[m].getPresence().isBot) {
									removeFromTable(m);
									_players[m] = null;
								}
							}
						}
						countHandsBotOnly = 0;
					}
				}
			}

			//find a friend feature
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null && !isRemoved(_players[m]) && !isLeft(_players[m])) {
					_players[m].getPresence().player().setActiveGame(0);
					_players[m].getPresence().player().setActiveTable(_tid);
				}
			}
		}

		public int findPos(Presence p) {
			for (int k = 0; k < MAX_PLAYERS; k++) {
				if (_players[k] != null
						&& _players[k].getName().compareToIgnoreCase(p.name()) == 0) {
					return k;
				}
			}
			return -1;
		}

		private void initGame() {
			_cat.debug("have to come here to clear out all stuff...."
					+ " on table : " + _tid);
			finalResultRuns = "";
			finalResultRuns3 = "";
			finalResultRuns1 = "";
			finalResultRuns2 = "";

			idPlayerChecked = -1;

			idPlayerValidDeclared = -1;

			countPlayerResponseDeclareLoser = 0;

			_nextMoveExpTime = System.currentTimeMillis();

			_deck.clear();
			_discardDeck = new Card[65];
			_indexDiscardDeck = 0;

			totalJokersAlreadyDealt = 0;
			printedJokerDealt = false;

			// find the rummy card and the last card that will never come
			int randCard = drawCard();
			// if rummy card comes to be joker, then make Ace of Clubs as joker
			if (randCard >= 161) {
				_deck.clear(randCard);
				randCard = 12;
				_deck.set(randCard);
			}
			_rummyCardJoker = new Card(randCard);
			_rummyCardJoker.setIsOpened(true);

			randCard = drawCard();
			_discardCard = new Card(randCard);
			_discardCard.setIsOpened(true);

			_discardDeck[_indexDiscardDeck++] = _discardCard;

			_prevDiscardCard = new Card(randCard);

			indicesCardsInRun = new String[13]; // it will have entries stored
												// as count'7`8`9|2`3`4 - will
												// be helpful in computing
												// penalty
			for (int i = 0; i < 13; i++)
				indicesCardsInRun[i] = "";

			newCardAdded = "";
			globalJokerTracker = "";
			bitsetJoker = new BitSet();
			bitsetPlyrCards = new BitSet();
			removedCardsDupli = new int[6];// expecting at most 6 pairs
			indexDupli = 0;
			playerStatus = new int[52];
			for (int i = 0; i < 6; i++) {
				removedCardsDupli[i] = -1;
			}
			for (int i = 0; i < 52; i++) {
				playerStatus[i] = 0;
			}

			_lastMovePos = -1;
			_lastMove = 0;
			_lastMoveString = "";
		}

		public int getIndex() {
			return _tid;
		}

		public int getMaxPlayers() {
			return MAX_PLAYERS;
		}

		public int getCurrentPlayersCount() {
			int actualCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && _players[i].isRummyPlayer())
					actualCount++;
			}
			return actualCount;
		}

		public String getCurrentPlayersDetail() {
			StringBuffer det = new StringBuffer();
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && _players[i].isRummyPlayer()) {
					det.append(_players[i].getName() + "`"
							+ _players[i].getRUMMYStatus());
					det.append("`").append(_players[i].pos());
					det.append("|");
				}
			}
			if (det.length() > 1)
				det.deleteCharAt(det.length() - 1);
			return det.toString();
		}

		public void addPresence(RummyProfile p) {
			_players[p.pos()] = p;
		}

		public int findFirstAvailPos() {
			int i = 0;
			for (i = 0; i < MAX_PLAYERS; i++) {
				// dealer and rummyplayer even if left have to be maintained -
				// no one can sit in their place
				if (_players[i] == null)
					return i;
			}
			return -1;
		}

		public void handleGameJoinReq(int obspos, Presence pold) {
			// get the player
			RummyProfile p = null;
			boolean rejoinReq = false;

			// check if the player was playing before he decided to logout, now
			// he wants to come back
			p = findProfile(pold.name());
			if (p != null)
				rejoinReq = true;

			if (p == null)
				p = findObservor(pold.name());

			if (p == null) {
				_cat.debug("can't find the player in observors list or old players : "
								+ obspos);
				return;
			}

			// if (_gameOngoing) {
			// //game in progress, no one can join now, wait for game over
			// _cat.debug("no one joins during game!!!");
			// return;
			// }

			_cat.debug("player found : " + p.getName() + " , rejoin : "
					+ rejoinReq);
			int pos = -1;
			if (!rejoinReq) {
				int _countPlayersInit = getCountActivePlayers();
				_cat.debug("found " + _countPlayersInit
						+ " players on table ");
				if (_countPlayersInit >= 2 || fixingDealerOngoing
						|| _gameOngoing) {

					pos = adjustNextToDealer(p);
					if (pos == -1) {
						_cat.debug("despite attempts no avail pos at table :"
										+ _tid + " for player : " + p.getName());
						return;
					}
					
					chatOn = 0;
					chatMessage = _players[pos].name + " at pos : " + (pos + 1) + " joined.";
					
					_players[pos].getPresence().player().setActiveGame(0);
					_players[pos].getPresence().player().setActiveTable(_tid);
					
					_players[pos].setTimeBank(30000);

					_cat.debug("new nextmovepos : " + _nextMovePlayer
							+ " , new rummy player tag : " + _rummyPlayer);
					p.setRUMMYStatus(status_JOINED);
				} else {
					// less than 2 players - call resettable once
					resetTable();
					_countPlayersInit = getCountTotalPlayers();

					if (_countPlayersInit == 0) {
						pos = 0;// very first player
						_players[0] = p;
						_nextMoveExpTime = System.currentTimeMillis();
					} else if (_countPlayersInit == 1) {
						//check if the already seated plaeyr is on pos 1.
						//if so, give pos 0 to the incoming player
						//after all, there is only 1 player on table. 0 is empty
						if (_players[1] != null)
							pos = 0;
						else
							pos = 1;
						_players[pos] = p;
						_nextMoveExpTime = System.currentTimeMillis();
					}
					
					chatOn = 0;
					chatMessage = _players[pos].name + " at pos : " + (pos + 1) + " joined.";
					
					_players[pos].getPresence().player().setActiveGame(0);
					_players[pos].getPresence().player().setActiveTable(_tid);
					
					_players[pos].setTimeBank(30000);
					
					p.setRUMMYStatus(status_ACTIVE);// the first 2 players have
													// to be marked active
				}
			} else {
				//a player can't join back immediately on the same table he left. it could be a ploy to cheat the house
				//TBD - check if currently playing players list has this name. if so, then kick him out
				//else allow him
				pos = p._pos;
				if (!isFolded(p))
					p.setRUMMYStatus(status_JOINED);
				else
					p.setRUMMYStatus(status_FOLDED);//removing the REMOVED status
				
				chatOn = 0;
				chatMessage = _players[pos].name + " at pos : " + (pos + 1) + " rejoined.";
			}

			removeObserver(p);

			// now make him seat on the table on that position
			p.setPresence(pold);
			p.setPos(pos);
			p.setRummyPlayer();
			p.getPresence().setRUMMYTID(_tid);
			p.getPresence().lastMove(Moves.RUMMY_SITIN);
			p.setRUMMYMoveId(0);

			addPresence(p);
			_cat.debug("RummyServer game - sit in req buf -- on table : "
							+ _tid);
			// send message to all players about the new player
			_lastMove = move_RUMMY_JOINED;
			_lastMovePos = pos;
			_lastMoveString = "Joined";

			p.rummyLeftTableTime = -1;
			p.rummyLastMoveTime = System.currentTimeMillis();

			int countPlayers = getCountActivePlayers();
			if (countPlayers == 1 && !_gameOngoing) {
				_winnerPos = -1;
			} else if (countPlayers == 2 && !_gameOngoing) {
				_winnerPos = -1;
				// fixing dealer process already on, then no need to start it
				// again
//				if (!fixingDealerOngoing) {
//					fixingDealerNextHand = true;
//					fixDealerFirst();
//				}
			} else {
				// not calling start game from here - that has to be done from
				// run method
				if (_gameOngoing) {
//					fixingDealerNextHand = true;
					p.setRUMMYStatus(status_JOINED);
				}
				else {
					p.setRUMMYStatus(status_JOINED);
				}
			}
			
			broadcastMessage(-1);
		}

		public void removePresence(RummyProfile p) {
			_players[p.pos()] = null;
		}

		public RummyProfile getPresence(int index) {
			return _players[index];
		}

		public boolean isActive(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_ACTIVE) > 0) {
				return true;
			}
			return false;
		}

		public boolean isRemoved(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_REMOVED) > 0) {
				return true;
			}
			return false;
		}

		public boolean isLeft(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_LEFT) > 0) {
				return true;
			}
			return false;
		}

		public boolean isWinner(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_WINNER) > 0) {
				return true;
			}
			return false;
		}

		public boolean isFolded(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_FOLDED) > 0) {
				return true;
			}
			return false;
		}

		public boolean isBroke(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_BROKE) > 0) {
				return true;
			}
			return false;
		}

		public boolean isSittingOut(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_SITTINGOUT) > 0) {
				return true;
			}
			return false;
		}

		public boolean isJoined(RummyProfile p) {
			if ((p.getRUMMYStatus() & status_JOINED) > 0) {
				return true;
			}
			return false;
		}

		public int getCountActivePlayers() {
//			_cat.debug("getcntactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i])
						&&
						// !isFolded(_players[i]) &&
						!isRemoved(_players[i]) && !isSittingOut(_players[i])
						&& !isJoined(_players[i]) && !isBroke(_players[i]))
					cnt++;
			}

			return cnt;
		}

		public int getCntStatusActivePlayers() {
//			_cat.debug("getcntstatusactpl : ");
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && isActive(_players[i]))
					cnt++;
			}

			return cnt;
		}

		public int getCountTotalPlayers() {
			int cnt = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null && !isLeft(_players[i])
						&& !isRemoved(_players[i]) && !isBroke(_players[i]))
					cnt++;
			}

			return cnt;
		}
		
		public int getCountSitInReqPlayers() {
			return sitinPlayers.size();
		}

		public int getRightOfPlayer(int pos) {
			int pos1 = decrPos(pos);
			if (pos1 == -1) {
				// seems like we just went around and still can't find next
				// player
				pos1 = 5;
			}
			// having ensured that this pos player is not null, let us
			// verify if status of this player allows game to proceed
			int countCalls = 0;
			while ((_players[pos1].getRUMMYStatus() & status_FOLDED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_REMOVED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_BROKE) > 0
					|| (_players[pos1].getRUMMYStatus() & status_SITTINGOUT) > 0
					|| (_players[pos1].getRUMMYStatus() & status_JOINED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);

				pos1 = decrPos(pos1);
				countCalls++;
				if (countCalls >= MAX_PLAYERS || pos1 == -1 || pos1 == pos) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("next right pos : " + pos1 + " on table : "
					+ _tid);
			return pos1;
		}

		private int decrPos(int pos) {
			if (pos < 0)
				return -1;
			
			int origPos = pos;
			// get the next position
			if (pos == 0) {
				pos = MAX_PLAYERS - 1;
			} else
				pos--;

			while (_players[pos] == null) {
				pos--;
				if (pos < 0)
					pos = MAX_PLAYERS - 1;

				if (pos == origPos)
					return -1;
			}
			_cat.debug("decrpos player pos : " + pos + " on table : "
					+ _tid + ", from old pos : " + origPos);
			return pos;
		}

		public int getOnlyActivePos() {
			int pos1 = 0; // start from 0th index
			if (_players[pos1] == null) {
				pos1 = incrPos(pos1);
				if (pos1 == -1) {
					return -1;
				}
			}

			int countCalls = 0;
			while ((_players[pos1].getRUMMYStatus() & status_ACTIVE) <= 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);

				pos1 = incrPos(pos1);
				countCalls++;
				if (countCalls >= MAX_PLAYERS || pos1 == -1) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("only active pos : " + pos1 + " on table : "
					+ _tid);
			return pos1;
		}

		public int getNextActivePos(int pos) {
			int pos1 = incrPos(pos);
			if (pos1 == -1) {
				// seems like we just went around and still can't find next
				// player
				return -1;
			}
			// having ensured that this pos player is not null, let us
			// verify if status of this player allows game to proceed
			int countCalls = 0;
			while ((_players[pos1].getRUMMYStatus() & status_FOLDED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_REMOVED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_BROKE) > 0
					|| (_players[pos1].getRUMMYStatus() & status_SITTINGOUT) > 0
					|| (_players[pos1].getRUMMYStatus() & status_JOINED) > 0
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);

				pos1 = incrPos(pos1);
				countCalls++;
				if (countCalls >= MAX_PLAYERS || pos1 == -1 || pos1 == pos) {
					// already this method has been called max times, nothing
					// comes out of it
					return -1;
				}
			}
			_cat.debug("next active pos : " + pos1 + " on table : "
					+ _tid);
			return pos1;
		}

		private int incrPos(int pos) {
			int origPos = pos;
			// get the next position
			if (pos == MAX_PLAYERS - 1) {
				pos = 0;
			} else
				pos++;

			while (_players[pos] == null) {
				pos++;
				if (pos == MAX_PLAYERS)
					pos = 0;
				if (pos == origPos)
					return -1;
			}
			_cat.debug("incrpos player pos : " + pos + " on table : "
					+ _tid + ", from old pos : " + origPos);
			return pos;
		}

		private void handleMoveNoAction(int pos) {

			// if (isRemoved(_players[pos])) {
			// return;
			// }
			
			//if a newly joined player sees that he is uanble to join current agme
			//he may want to leave table. his status will be joined + sitting out
			if (_gameOngoing){
				if (isSittingOut(_players[pos])){
					_cat.debug("player is sitting out");
					removeFromTable(pos);
					_players[pos] = null;
					return;
				}
			}

			// always find the new dealer if player is leaving a table
//			fixingDealerNextHand = true;
			_lastMovePos = pos;
			_lastMoveString = "Left";

			if (_nextMovePlayer != 111) {

				if (pos == _nextMovePlayer) {
					// same action as that of player leaving table
					handleTimedOut(true);
					return;
				}
				
				chatOn = 0;
				chatMessage = _players[pos].name + " at pos : " + (_players[pos].pos() + 1) + " left.";

				// not the player making move, but still mark it as folded, set
				// penalty
				if (_players[pos]._firstMoveDone)
					_players[pos].setRUMMYPoints(40);
				else {
					_players[pos].setRUMMYPoints(20);
					// player didn't even play one card, so put his cards in
					// fresh
					// pile
					addCardsToFreshPile(_players[pos]._allotedCards, 13);
				}

				_players[pos]._allotedCards = null;

				// if there are only 2 players and one of them di the hooky then
				// the
				// other player has to win
				// now if the leaving player is nextmoveose it is handled above
				// but
				// the opposite might be true too :-( - that is winking, not sad
				if (!checkGameOver()) {
					// game not over
					broadcastMessage(-1);
				}
			}
		}

		private void handleTimedOut(boolean left) {
			if (_players[_nextMovePlayer]._firstMoveDone)
				_players[_nextMovePlayer].setRUMMYPoints(40);
			else {
				_players[_nextMovePlayer].setRUMMYPoints(20);
				// player didn't even play one card, so put his cards in fresh
				// pile
				addCardsToFreshPile(_players[_nextMovePlayer]._allotedCards, 13);
			}

			_lastMovePos = _nextMovePlayer;
			_lastMoveString = "FoldedDueToInaction";
			
			chatOn = 0;
			chatMessage = _players[_nextMovePlayer].name + " at pos : " + (_nextMovePlayer + 1) + " timed out.";
			
			
			if (_players[_nextMovePlayer].isEligibleTimeBank()) {
				_players[_nextMovePlayer].setRUMMYMovesStr("&Folded");
				if (!_players[_nextMovePlayer].getPresence().isBot){
					if (!left)
						_players[_nextMovePlayer].setRUMMYStatus(status_FOLDED);
					else
						_players[_nextMovePlayer].setRUMMYStatus(status_REMOVED);
				}
				else {
					_players[_nextMovePlayer].setRUMMYStatus(status_REMOVED);
					_players[_nextMovePlayer].setTimeBank(-1);
					_players[_nextMovePlayer].setUsingTimeBank(false);
				}
			}
			else {
				//mark the player as left for he has now exhausted time bank
				_players[_nextMovePlayer].setRUMMYMovesStr("&TimedOut");
				_players[_nextMovePlayer].setRUMMYStatus(status_REMOVED);
			}
			
			_cat.debug("player timed out " + _nextMovePlayer);
			_nextMoveExpTime = System.currentTimeMillis();

			if (!checkGameOver()) {
				// game not over
				_nextMovePlayer = getRightOfPlayer(_nextMovePlayer);
				_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
						| move_RUMMY_FOLD;
				broadcastMessage(-1);
			}
		}

		// posWinner tells us which one won out or in - many players might have
		// bet on out/in
		public String declareGameOver(int posWinner) {
			_nextMoveExpTime = System.currentTimeMillis();
			_gameOngoing = false;
			_winnerPos = posWinner;
			String resString = "";
			StringBuffer sb = new StringBuffer();
			int countPotContenders = 0;
			//to hold max number of players
			final String[] names = new String[6];

			boolean botPresent = false;
			
			int totalWinPts = 0;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& (isActive(_players[m]) || isLeft(_players[m]) || isRemoved(_players[m]) || isFolded(_players[m]))) {
					// compute the points of players still playing
					int pts = 0;
					if (m != _winnerPos) {
						if (isFolded(_players[m]) || isLeft(_players[m]) || isRemoved(_players[m]))
							pts = _players[m].getRUMMYPoints();
						else {
							idPlayerChecked = m;
							int[] retVal = checkValidCards2(
									_players[m]._allotedCards,
									_players[m].cardsStrFromClient);
							pts = retVal[0];
						}

						_players[m].setRUMMYPoints(pts);
						
						if (!_players[m].getPresence().isBot){
							_players[m].getPresence().currentRoundBet(pts * POINTSPERRUPEE);
							_amtWonString += m + "'" + (pts * POINTSPERRUPEE * -1) + "|";
						}
						else {
							_players[m].setBetAmt(pts * POINTSPERRUPEE);
							botPresent = true;
							_cat.debug("server declaregameover : we have a bot here...");
						}
							
						totalWinPts += pts;
						
						_players[m].setRUMMYMovesStr("&Penalty^" + pts);
					}
					
					//add winner declaration
					_players[m].setRUMMYMovesStr("&Winner^" + _players[_winnerPos].getName());
					
					sb.append(m + "`" + _players[m].name + "`" + pts + "'");
					names[countPotContenders++] = _players[m].name;
				}
			}

			sb.deleteCharAt(sb.length() - 1);
			
			if (botPresent){
				countHandsBotOnly++;
				_cat.debug("BOTS HANDS COUNT : :: :::: " + countHandsBotOnly);
			}
			

			dbwinamt = totalWinPts * POINTSPERRUPEE;
			_rake = dbwinamt * RAKE_PERCENTAGE / 100;
			dbwinamt -= _rake;
			// sb.append(_winnerPos + "`" + winamt + "'");
			_players[_winnerPos].setRUMMYWinAmt(totalWinPts);
			
			if (!_players[_winnerPos].getPresence().isBot){
				_players[_winnerPos].getPresence().addToWin(dbwinamt);
				_amtWonString += _winnerPos + "'" + dbwinamt;
			}
			
			_players[_winnerPos].setRUMMYMovesStr("&WinPoints^" + totalWinPts);

			// hack - comment later
			_cat.debug("winners win amt computd");
			_cat.debug("total players in hand : " + countPotContenders);
			
			dbpotcontendors = countPotContenders;

			new Thread(){
				public void run(){
					// loop thru the players, find the participating players
					// insert their entries in t_player_per_grs
					GameRunSession grs = new GameRunSession(_gid, rummygrid, GameType.Rummy);//8192 is the value for indian rummy
		//					_type.intVal());
					grs.setEndTime(new Date());
					grs.setStartTime(_gameStartTime.getTime());
					double rake[];
					rake = Utils.integralDivide(_rake, dbpotcontendors);
		
					// ************STARTING BATCH
					int j = -1;
					try {
						Statement grs_stat = grs.startBatch();
						for (int i = 0; i < MAX_PLAYERS; i++) {
							// _playersInHand
							if (_players[i] == null)
								continue;
		
							RummyProfile pr = _players[i];
							j++;
		
							GamePlayer gp = (GamePlayer) pr.getPresence().player();
							grs.setDisplayName(gp.name());
							grs.setPosition(pr.pos());
							grs.setPot(dbwinamt + _rake);
							double betamnt = pr.getPresence().currentRoundBet();
							if (pr.getPresence().isBot)
								betamnt = pr.getBetAmt();
							
							grs.setStartWorth(pr.getGameStartWorth());
		
							grs.setWinAmount(0);
							// for winner only these 2 lines
							if (i == _winnerPos) {
								// win amt will be actual win amt after accounting for
								// bet amt
								grs.setWinAmount(dbwinamt);
								grs.setEndWorth(pr.getGameStartWorth() + dbwinamt
										- betamnt);
							} else {
								grs.setEndWorth(pr.getGameStartWorth() - betamnt);
							}
							grs.setBetAmt(betamnt);
							// now for all
							grs.setSessionId(gp.session());
							grs.setRake(rake[j]);
		
							grs.setMoveDet(pr.getRUMMYMovesStr());
							grs.save(grs_stat);
		
							// now set the player's start worth
							_players[i].setGameStartWorth(grs.getEndWorth());
						}
						// COMMITTING BATCH
						grs.commitBatch(grs_stat);
						_cat.debug("grs committed...");
						// TBD- login session have to be updated with win and bet
					} catch (Exception ex) {
						_cat.debug("Exception - " + ex.getMessage());
					}
				}
			}.start();
			
			//for t_user_eap, this is the only place
			new Thread(){
				public void run(){
					double rake[];
					rake = Utils.integralDivide(_rake, dbpotcontendors);
					
					for (int m = 0; m < dbpotcontendors; m++){
						if (names[m] == null)
							continue;
						DBEAP dbeap = new DBEAP(names[m], rake[m]);
						try {
							dbeap.setUserEAP();
						} catch (DBException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}.start();
			
			//so that sit out and sit in can be be displayed along with result window
			handleSitoutSitin();
			
			resString = countPotContenders + ":" + sb.toString();
			return resString;
		}

		void addCardsToFreshPile(Card[] crs, int index) {
			if (crs == null)
				return;

			for (int i = 0; i < index; i++) {
				if (crs[i].getIndex() > -1) {
					_deck.clear(crs[i].getIndex());
				}
			}
		}

		boolean checkGameOver() {
			int cntActPlyrs = getCntStatusActivePlayers();
			if (cntActPlyrs < 2) {
				// game can't proceed, the lone ranger is the winner
				if (cntActPlyrs >= 1) {
					int pos = getOnlyActivePos();
					if (pos != -1 && _gameOngoing) {
						//check here if the active player has his time bank on
						if (_players[pos].getUsingTimeBank()){
							_players[pos].setTimeBank(_players[pos].getTimeBankExpTime() - System.currentTimeMillis());
							_players[pos].setUsingTimeBank(false);
							_cat.debug("ha! player with tb now shut : " + _players[pos].getUsingTimeBank() + " for pos " + pos);
						}
						_cat.debug("player timed out at time of declaration");
						_winnerString = declareGameOver(pos);
						broadcastMessage(pos);
					} else {
						// for some reaons either game is not on or pos is -1
						_gameOngoing = false;
					}
				} else {
					// less than 1 player on table - sad state of affairs
					_gameOngoing = false;
				}

				return true;
			} else {
				return false;
			}
		}

		int adjustNextToDealer(RummyProfile rp) {
			_cat.debug("trying to adjust next to dealer for pos : "
					+ rp.pos());
			// if a pos is availabe
			int pos = findPosWhoseNextIsFreePos();
			_cat.debug("found a free pos : " + pos);
			if (pos != -1) {
				// if this player is the dealer, balle balle - no need to shift
				// any player
				// if this is some other player, then we have to shift all the
				// players starting from dealer left to max valid pos
				// so that we make space for new player -- this will be done in
				// adjustNexttoDealer
				if (_dealer == pos) {
					_players[pos + 1] = rp;
					rp._pos = pos + 1;
					// shift the dealer after game is over
					return rp.pos();
				}

				// the dealer is somewhere in the middle of players, start
				// shifting the players above the dealer
				// for (int i = pos; i > _dealer; i--) {
				// _players[i + 1] = _players[i];
				// _players[i+1]._pos = i+1;
				// _players[i] = null;
				//
				// //shift the _nextmovePos and _rummyPlayer tag
				// if (_rummyPlayer == i)
				// _rummyPlayer = i + 1;
				// if (_nextMovePlayer == i)
				// _nextMovePlayer = i + 1;
				// }
				_players[pos + 1] = rp;
				_players[pos + 1]._pos = pos + 1;
				return rp.pos();
			}

			return -1;
		}

		int findPosWhoseNextIsFreePos() {
			int retPos = -1;
			// now find the max pos of player whose next left is empty
			// from this method we only send the max valid pos of player whose
			// next left is empty
			// error case : if 1st pos is empty, check it and return right away
			// if (_players[0] == null) {
			// return -1;
			// }

			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (i + 1 >= MAX_PLAYERS) {
					if (_players[5] != null && _players[0] == null)
						return 5;
				}

				if (_players[i] != null && _players[i + 1] == null)
					return i;
			}

			return retPos;
		}

		// Vikas
		// ****************************************************************************************************
		Card[] removeAllCardsOfRun2(String cards, String runs) {
			String[] toks1 = cards.split("\\|");
			// toks1 has the groups now. the indices to be removed are there in
			// runs
			String[] runToks1 = runs.split("\\'");
			int[] runTks = new int[runToks1.length];
			for (int i = 0; i < runTks.length; i++) {
				runTks[i] = Integer.parseInt(runToks1[i]);
			}

			int lenRetCards = 0;
			String retCards = "";
			for (int i = 0; i < toks1.length; i++) {
				// go thru the orig array of groups. find the length of cards
				// needed to be penalized
				boolean found = false;
				for (int j = 0; j < runTks.length; j++) {
					if (i == runTks[j]) {
						found = true;
						break;
					}
				}

				if (!found) {
					String[] toks2 = toks1[i].split("\\`");
					lenRetCards += toks2.length;
					retCards += toks1[i] + "'";
				}
			}
			if (!retCards.isEmpty()) {
				retCards = retCards.substring(0, retCards.length() - 1);
			}

			// now get the returning cards for penalty
			if (lenRetCards > 0) {
				Card[] toRetCards = new Card[lenRetCards];
				int indexRetCards = 0;

				String[] tokis = retCards.split("\\'");
				for (int m = 0; m < tokis.length; m++) {
					String[] toks2 = tokis[m].split("\\`");
					for (int k = 0; k < toks2.length; k++) {
						toRetCards[indexRetCards++] = new Card(toks2[k]);
//						_cat.debug("cards Pending : " + toks2[k]);
					}
				}

				if (indexRetCards > 0)
					return toRetCards;
				else
					return null;
			}

			return null;
		}

		boolean determineIfJoker(Card crd) {
			if (crd.getIndex() >= 156)
				return true;

			int dummy = 0;
			dummy = _rummyCardJoker.getRank();
			// first check the cut joker
			if (crd.getRank() == dummy)
				return true;

			return false;
		}

		boolean CheckIfSets(String[] toks2) {
			_cat.debug("checking sets with joker or no joker");
			int jokerCount = 0, playerCount = 0;
			Card[] playerCards = new Card[4];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				if (determineIfJoker(cr))
					jokerCount++;
				else {
					playerCards[playerCount++] = cr;
				}
			}

			if (playerCount == 1 && jokerCount >= 2) {
				// can put it as impure run or set. goign by set logic
				return true;
			} else {
				if (jokerCount == 2) {
					// 2 jokers and 2 cards can make a 4 card set - if it were 1
					// card with 2 jokers - case handled above
					if (playerCount == 2) {
						// other two have to be pair else it is an epic fail
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1]);
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					}
				} else if (jokerCount == 1) {
					if (playerCount == 2) {
						// other two have to be pair else it is an epic fail
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1]);
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					} else if (playerCount == 3) {// it is a 4 card set with one
													// joker
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1] + " , " + playerCards[2]);
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[2]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[2]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					}
				} else if (jokerCount == 0) {
					// all three cards have to have same rank now
					_cat.debug("set : " + playerCards[0] + " , "
							+ playerCards[1] + " , " + playerCards[2]);
					if (playerCount == 3) {
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[2]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[2]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					} else if (playerCount == 4) {
						// 4 card pure set
						_cat.debug("set : " + playerCards[0] + " , "
								+ playerCards[1] + " , " + playerCards[2]
								+ " , " + playerCards[3]);
						if (playerCards[0].getRank() == playerCards[1]
								.getRank()
								&& playerCards[0].getIndex() != playerCards[1]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[2]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[2]
										.getIndex()
								&& playerCards[0].getRank() == playerCards[3]
										.getRank()
								&& playerCards[0].getIndex() != playerCards[3]
										.getIndex()) {
							// it is a set - don't want dublee here
							return true;
						}
					}
				}
			}
			return false;
		}

		boolean CheckPureRuns(String[] toks2) {
			_cat.debug("pure run checking");
			int playerCount = 0;
			Card[] playerCards = new Card[toks2.length];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				playerCards[playerCount++] = cr;
			}

			// having jokers is not a criteria to reject the pure run. perhaps
			// joker is being used as is what is

			ArrayList<Integer> cardsArr = new ArrayList();
			for (int index = 0; index < toks2.length; index++) {
				cardsArr.add(new Card(toks2[index]).getIndex());
			}
			Collections.sort(cardsArr);

			int suitRun = -1;
			int[] cardTwo = new int[toks2.length];
			for (int i = 0; i < toks2.length; i++) {
				// these cards have to be of same suit or else no run is
				// possible - brouhaahaha
				cardTwo[i] = cardsArr.get(i);
				if (suitRun == -1)
					suitRun = cardTwo[i] / 13;
				else {
					int newsuit = cardTwo[i] / 13;
					if (suitRun != newsuit)
						return false;// cards not of same suit, nuff said
				}
			}

			// the cardTwo array has sorted cards - if this is a pure run, the
			// cards have to be in ascending order
			boolean runpure = true;
			int beginIndex = cardTwo[0];
			for (int i = 1; i < toks2.length; i++) {
				if (beginIndex + 1 != cardTwo[i]) {
					runpure = false;
					break;
				}
				beginIndex = cardTwo[i];
			}

			if (!runpure) {
				// check for the case of Ace, Two, Three et al. for these cases
				// as Ace is at the end the condition will fail
				if (cardTwo[toks2.length - 1] % 13 == Card.ACE) {
					runpure = true;
					_cat.debug("checknig pure run wiht Ace");
					beginIndex = -1; // so that when you add 1 you get 0 which
										// is Card.TWO
					for (int i = 0; i < toks2.length - 1; i++) {
						if (beginIndex + 1 != cardTwo[i] % 13) {
							runpure = false;
							break;
						}
						beginIndex = cardTwo[i] % 13;
					}
				}
			}

			return runpure;
		}

		boolean CheckImPureRuns(String[] toks2) {
			_cat.debug("impure run check");
			int playerCount = 0, jokerCount = 0;
			Card[] playerCards = new Card[toks2.length];

			for (int i = 0; i < toks2.length; i++) {
				Card cr = new Card(toks2[i]);
				if (determineIfJoker(cr))
					jokerCount++;
				else {
					playerCards[playerCount++] = cr;
				}
			}

			if (playerCount == 1 && jokerCount >= 2)
				return true;
			if (playerCount == 0)
				return false;

			// there will be jokers and they will be completing the run.
			// find the total gap that needs to be covered by jokers. then check
			// if you have that many jokers in the group
			// if so, then balle balle. if not, then what yaar, try next one!

			ArrayList<Integer> cardsArr = new ArrayList();
			for (int index = 0; index < playerCount; index++) {
				cardsArr.add(playerCards[index].getIndex());
			}
			Collections.sort(cardsArr);

			int suitRun = -1;
			int[] cardTwo = new int[playerCount];
			for (int i = 0; i < playerCount; i++) {
				// these cards have to be of same suit or else no run is
				// possible - brouhaahaha
				cardTwo[i] = cardsArr.get(i);
				if (suitRun == -1)
					suitRun = cardTwo[i] / 13;
				else {
					int newsuit = cardTwo[i] / 13;
					if (suitRun != newsuit)
						return false;// cards not of same suit, nuff said
				}
			}

			// the cardTwo array has sorted cards - if this is a pure run, the
			// cards have to be in ascending order
			int jokerNeeded = 0;
			int beginIndex = cardTwo[0];
			for (int i = 1; i < playerCount; i++) {
				if (beginIndex + 1 != cardTwo[i]) {
					// we need joker or jokers to move from beginIndex to
					// cardTwo[i]
					int value = cardTwo[i] - beginIndex - 1;
					// this is a very special case - suppose there are 2 of 10S
					// and
					// we have a joker
					// our logic will give value as -1 and that is less than 1
					// so it
					// will treat it as a valid sequence
					// not now.
					if (value < 0)
						return false;

					jokerNeeded += value;
				}
				beginIndex = cardTwo[i];
			}

			if (jokerNeeded <= jokerCount)
				return true;
			else {
				// there is a special case of Ace where it can be used with Two
				// and Three and others to make a run
				if (cardTwo[playerCount - 1] % 13 == Card.ACE) {
					_cat.debug("checknig impure run wiht Ace");
					// last card is an Ace - go for it
					jokerNeeded = 0;
					beginIndex = -1;
					for (int i = 0; i < playerCount - 1; i++) {
						if (beginIndex + 1 != cardTwo[i] % 13) {
							// we need joker or jokers to move from beginIndex
							// to cardTWo[i]
							int indCard = cardTwo[i] % 13;
							int indCard2 = beginIndex + 1;
							int val = indCard - indCard2;
							if (val < 0)
								return false;

							jokerNeeded += val;
						}
						beginIndex = cardTwo[i] % 13;
					}

					if (jokerNeeded <= jokerCount)
						return true;
					else
						return false;
				}

				return false;
			}
		}

		int findPenalty(String[] toks) {
			Card[] cards = new Card[toks.length];
			for (int i = 0; i < toks.length; i++) {
				cards[i] = new Card(toks[i]);
			}

			return computePoints2(cards);
		}

		String countRunsAsArranged(String cardsstr) {
			int pureRuns = 0, impureRuns = 0, numSets = 0, tanalaCount = 0;
			String pureRunGroup = "";
			String allRunsSetsGroup = "";
			boolean runGT4 = false;
			// these 2 variables only for pure runs and tanala - to be used if
			// only one pure run is to be exempted
			int highPenalty = 0;
			int highPenIndex = -1;

			String[] toks1 = cardsstr.split("\\|");
			for (int i = 0; i < toks1.length; i++) {
				boolean somethingfound = false;
				_cat.debug("to check : " + toks1[i]);
				String[] toks2 = toks1[i].split("\\`");
				// got the cards of one group
				// there can be these cases - a pure run, an impure run, a pure
				// set, an impure set, a tanala
				if (toks2.length == 3) {
					// could be a tanala
					if (toks2[0].compareToIgnoreCase(toks2[1]) == 0
							&& toks2[0].compareToIgnoreCase(toks2[2]) == 0) {
						tanalaCount++;
						pureRunGroup += i + "'";
						somethingfound = true;
						_cat.debug("tanala : " + toks1[i]);
						int pen = findPenalty(toks2);
						if (pen > highPenalty) {
							highPenalty = pen;
							highPenIndex = i;
						}
					}
				}

				if (toks2.length >= 3 && !somethingfound) {
					// check for run
					if (CheckPureRuns(toks2)) {
						pureRuns++;
						pureRunGroup += i + "'";
						somethingfound = true;
						if (toks2.length >= 4)
							runGT4 = true;
						_cat.debug("pure run : " + toks1[i]);
						int pen = findPenalty(toks2);
						if (pen > highPenalty) {
							highPenalty = pen;
							highPenIndex = i;
						}
					} else if (CheckImPureRuns(toks2)) {
						impureRuns++;
						allRunsSetsGroup += i + "'";
						somethingfound = true;
						if (toks2.length >= 4)
							runGT4 = true;
						_cat.debug("impure run : " + toks1[i]);
					}

					if (!somethingfound
							&& (toks2.length == 3 || toks2.length == 4)) {
						// could be a set -. take care. there could be a joker
						// here
						if (CheckIfSets(toks2)) {
							numSets++;
							allRunsSetsGroup += i + "'";
							somethingfound = true;
							_cat.debug("set : " + toks1[i]);
						}
					}
				}
				_cat.debug("pur run grp : " + pureRunGroup
						+ " , others group : " + allRunsSetsGroup);
			}

			// we have gone thru cards
			if (tanalaCount + pureRuns < 1) {
				// oooh, hand not valid, ooh, run like chicken
				return null;
			} else {
				// check now if there are 2 runs
				if (tanalaCount + pureRuns + impureRuns >= 2) {//had condition of runGT4 too!!!
					// valid hand - we meet our condition of having 2 runs one
					// of which is definitely a pure run
					pureRunGroup += allRunsSetsGroup;
					if (!pureRunGroup.isEmpty()) {
						pureRunGroup = pureRunGroup.substring(0,
								pureRunGroup.length() - 1);
					}
					return pureRunGroup;
				} else {
					// not valid hand, so just send the pure run to be removed
					// take care that only 1 run is to be exempted. if there are
					// 2 or 3 pure runs, take the one with highest penalty
					pureRunGroup = highPenIndex + "";
					return pureRunGroup;
				}
			}
		}

		int[] checkValidCards2(Card[] cards, String cardsStrFrmClnt) {

			int[] resTemp = new int[3];// 0 holds the points, 1 holds 1 if 1st
										// run
										// is valid else 0, 2 holds 1 if 2nd run
										// is
										// valid else 0

			// if the cards string from player is empty, most likely he/she
			// didn't send it - apply penalty of 120 and let it be
			if (cardsStrFrmClnt.isEmpty()) {
				_cat.debug("card string is empty...");
				resTemp[0] = 80;
				resTemp[1] = 0;
				resTemp[2] = 0;
				return resTemp;
			}

			_cat.debug("cards from client loser : "  + cardsStrFrmClnt);
			String restrVal = countRunsAsArranged(cardsStrFrmClnt);
			if (restrVal != null && !restrVal.isEmpty()) {
				// we do have 1st life.
				// we have 1st life but no second life - remove cards of
				// pure runs
				Card[] cardsPenal = removeAllCardsOfRun2(cardsStrFrmClnt,
						restrVal);

				resTemp[0] = computePoints2(cardsPenal);
				resTemp[1] = 1;
				resTemp[2] = 0;
				return resTemp;
			} else {
				// no first life - penalty on all cards
				resTemp = new int[3];
				resTemp[0] = computePoints2(cards);
				resTemp[1] = 0;
				resTemp[2] = 0;
				return resTemp;
			}
		}

		int computePoints2(Card[] cards) {
			int val = 0;
			if (cards == null) {
				_cat.debug("error hi error");
				return val;
			}

			int num = cards.length;
			if (num == 0) {
				_cat.debug("error hi error");
				return val;
			}

			_cat.debug("compute pts");
			for (int i = 0; i < num; i++) {
				if (cards[i] == null)
					continue;
			}

			// use rummyJoker to get the value of jokers
			int dummy = 0;

			for (int i = 0; i < num; i++) {
				if (cards[i] == null)
					continue;

				if (cards[i].getIndex() >= 156)
					continue;

				if (cards[i].getRank() == _rummyCardJoker.getRank()) {
					val += 0;
				} else {
					dummy = cards[i].getHighBJRank();
					if (dummy > 10 || dummy == 0)
						dummy = 10;
					val += dummy;
				}
			}

			if (val > 80)
				val = 80;// this is the max that a player has to pay
			return val;
		}

		// Vikas
		// ****************************************************************************************************

		public void run() {
			while (_keepServicing) {
				String moveStr = null;
				try {
					Presence p = fetch(_tid);
					moveStr = p.getLatestMoveStr();
					_cat.debug("movestr from run : " + moveStr);
					if (moveStr != null) {
						processStr(moveStr, p);
					}
				} catch (NoSuchElementException e) {
				} catch (Exception ex) {
				}

				// now check if game is going to be ended - someone made a valid
				// declaration and we are waiting for all cards messages
				// idPlayerValidDeclared
				if (_gameOngoing
						&& idPlayerValidDeclared != -1
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 21000) { 
					_winnerString = declareGameOver(idPlayerValidDeclared);
					broadcastMessage(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					_nextMovePlayer = -1;
					_nextMoveExpTime = System.currentTimeMillis();
				}

				// now check if someone has timed out his moves
				if (_gameOngoing
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 30000) { 
					if (_nextMovePlayer != -1)
						{
						//3rd Aug 2017 - changes for disconnect protection using time bank
						RummyProfile rp = _players[_nextMovePlayer];
						boolean useTB = true;
						if (!rp.getUsingTimeBank()){
							if (rp.isEligibleTimeBank()){
								rp.setUsingTimeBank(true);
								broadcastMessage(-1);
							}
							else {
								useTB = false;
							}
						}
						else {
							//player is already using time bank
							if (System.currentTimeMillis() - rp.getTimeBankExpTime() >= 0){
								//time bank chunk has expired.
								useTB = false;
							}
						}
						
						if (!useTB)
							handleTimedOut(false);
					}
				}
				
				// maintenance job - shall we create a separate thread for it?
				// maintenance job - if there is only one player on a table
				for (int m = 0; m < MAX_PLAYERS && !_gameOngoing; m++) {
					if (_players[m] != null
							&& System.currentTimeMillis() - _players[m].rummyLeftTableTime > 7000
							&& System.currentTimeMillis() - _nextMoveExpTime > 7000
							&& (isLeft(_players[m]) || isRemoved(_players[m]))) {
						// removed condition for waiting player to make a move -
						// that was creating issues with players who are waiting
						// to join next game
						// System.out
						// ._cat.debug("nothing from this player, kick him out : "
						// + _players[m].getName());
						// before sending the message we should check if client
						// is showing the result window
						// if so, this message would curtail the beautiful,
						// rapturous experience of a winning player
						sendMessageKickedOut(m, 2);

						// remove the player
						removeFromTable(m);
						_players[m] = null;
						
						if (getCountTotalPlayers() > 0 && !toSendMsg){
							toSendMsg = true;
							resetTable();
							broadcastMessage(-1);
						}
					}
				}
				
				if (!_gameOngoing && !toSendMsg &&
						System.currentTimeMillis() - _nextMoveExpTime > 5000){
					//if a player has left, then the loop above it will execute
					//no need to come here
					toSendMsg = true;
					resetTable();
					broadcastMessage(-1);
				}

				if (!_gameOngoing && _nextMoveExpTime != -1
						&& System.currentTimeMillis() - _nextMoveExpTime > 8000) {
					int countPlayers = getCntStatusActivePlayers() + getCountSitInReqPlayers();
//					_cat.debug("total : " + getCountTotalPlayers() + ", count: " + countPlayers + ", counter : " + counterGameNotStarted);
					
					if (getCountTotalPlayers() >= 2 && counterGameNotStarted < 8) {
						if (countPlayers >= 2) {
							fixDealerFirst();
						}
						else {
							counterGameNotStarted++;
							resetTable();
							if (countPlayers == 1)
								broadcastMessage(-1);
						}
					} else {
						// clear out the jokers for it creates a wrong
						// impression
						_rummyCardJoker = null;
						_discardCard = null;
						_dealer = -1;
						_rummyPlayer = -1;
						
						if (System.currentTimeMillis() - _nextMoveExpTime > 15000) {
							boolean flag = false;
							for (int m = 0; m < MAX_PLAYERS; m++) {
								if (_players[m] != null && !isActive(_players[m])) {
									sendMessageKickedOut(m, 2);
									// remove the player
									removeFromTable(m);
									_players[m] = null;
									flag = true;
								}
							}
							
							if (flag){
								broadcastMessage(-1);
								counterGameNotStarted = 0;
								_nextMoveExpTime = System.currentTimeMillis();
							}
						}
						
						// remove the removed players now
						if (System.currentTimeMillis() - _nextMoveExpTime > 60000
								) {
							for (int m = 0; m < MAX_PLAYERS; m++) {
								if (_players[m] != null) {
									sendMessageKickedOut(m, 2);
									// remove the player
									removeFromTable(m);
									_players[m] = null;
								}
							}
							counterGameNotStarted = 0;
							_nextMoveExpTime = -1;
						}
					}
				}

				if (!_gameOngoing) {
					if (System.currentTimeMillis() - _nextMoveExpTime > 60000) {
						_nextMoveExpTime = System.currentTimeMillis();
						for (int b = 0; b < _observers.size(); b++) {
							RummyProfile pro = (RummyProfile) _observers.get(b);
							// check first if it is just an observor, if so,
							// remove it - there is no game so what will he
							// observe?
							if (pro.getRUMMYStatus() == 0)
								removeObserver(pro);
						}
					}
				}

				// now sleep for 300 ms - in the long run i see it as 10 ms or
				// something
				try {
					Thread.currentThread().sleep(1000);
				} catch (InterruptedException ee) {
					// continue
				}
			}
		}

		private int drawCard() {

			// check if the fresh pile has run out - if so, we need to add the
			// cards of discard pile to fresh pile
			if (_deck.cardinality() >= MAX_CARDS) {
				_cat.debug("WARNING!!!! fresh pile has run out!!!!");
				addCardsToFreshPile(_discardDeck, _indexDiscardDeck);
				_discardDeck = new Card[65];

				// keep the same discard card - don't change it
				_deck.set(_discardCard.getIndex());

				_indexDiscardDeck = 0;
				_discardDeck[_indexDiscardDeck++] = _discardCard;

				// also mark the rummy card joker - we can't give it if we have
				// only one deck
				_deck.set(_rummyCardJoker.getIndex());
			}

			int k = Rng.nextIntBetween(0, 100);
			if (k == 37 || k == 63) {
				// give a joker if it is possible
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			// also put a condition on deck size - if 1/2 deck is gone and no
			// printed joker is dealt, deliberately give a
			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.4)
					&& _deck.cardinality() < (int) (MAX_CARDS * 0.5)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.6)
					&& _deck.cardinality() < (int) (MAX_CARDS * 0.7)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.8)
					&& _deck.cardinality() < (int) (MAX_CARDS * 0.9)
					&& !printedJokerDealt) {
				if (totalJokersAlreadyDealt < NUM_DECKS) {
					totalJokersAlreadyDealt++;
					_deck.set(160 + totalJokersAlreadyDealt);
					printedJokerDealt = true;
					return 160 + totalJokersAlreadyDealt;
				}
			}

			int rand = Rng.nextIntBetween(0, MAX_CARDS);
			while (_deck.get(rand)) {
				rand = Rng.nextIntBetween(0, MAX_CARDS);
			}
			_deck.set(rand);
			// _cat.debug("found this card : " + rand +
			// " , max cards : "
			// + MAX_CARDS);

			if (_deck.cardinality() >= (int) (MAX_CARDS * 0.5)
					|| _deck.cardinality() >= (int) (MAX_CARDS * 0.7)
					|| _deck.cardinality() >= (int) (MAX_CARDS * 0.9))
				printedJokerDealt = false;

			if (rand >= 103)
				rand -= 103;
			else if (rand >= 52)
				rand -= 52;
			return rand;
		}

		// drawCardOneDeck
		private int drawCardOneDeck() {
			int rand = Rng.nextIntBetween(0, 51);
			while (_deck.get(rand)) {
				rand = Rng.nextIntBetween(0, 51);
			}
			_deck.set(rand);
			return rand;
		}

		public void processStr(String movedet, Presence p) {
			// 0,-1,0,143680.64,-1,-1
			int tid = getTID(movedet);// table id, which table he is seated
			int pos = getPos(movedet);// which chair position he is seated
			int moveId = getMoveId(movedet);
			String type = getType(movedet); // applicable for which pile a card
											// is requested - fresh pile or
											// discard pile
			String cardsDet = getCardsDet(movedet); // only applicable for
													// declare and declare
													// loser. has the various
													// cards arranged in groups

			// and also for the case of discarding card - index of card that is
			// being discarded
			_cat.debug("server :: player moves : tid : " + tid + " , pos : " + pos
					+ ", moveid : " + moveId + ",type : " + type
					+ " , nextmoveplayer : " + _nextMovePlayer);

			// first handle the case of sit in request
			if (moveId == move_RUMMY_JOINED) {
				// definitely a sit in request ---
				//check here if game is in declare mode expecting losers to send in their card string
				//if so, we can't let this player join the table - he/she has to wait till dust settles
				if (_nextMovePlayer != 111)
					handleGameJoinReq(pos, p);
				return;
			}
			
			//for bots. no registration. direct seating on table.
			//only making 2 bots sit at one table.
			//everytime bots have to be seated, we need to send request from client
			//for that table and the bot names who will be on that table.
			if (moveId == move_RUMMY_BOTREGISTER){
				//get list of bots from cardsdet
				String botsNames = getCardsDet(movedet);
				String[] names = botsNames.split("\\'");
				
				//now we have the names
				//cn=1, cn=2, cn=6 and cn=7&mv=10 for this table,
				//all to be done in one go
				//0th entry has to be the player who has already registered
				GamePlayer gp = (GamePlayer) p.player();
				for (int m = 0; m < names.length; m++){
					_cat.debug("calling bot creator for name : " + names[m]);
					
					//a bot can't join again.
					boolean found = false;
					int emptyPos = -1;
					for (int n = 0; n < _players.length; n++){
						if (_players[n] != null){
							if (_players[n].getPresence().isBot && 
									_players[n].name.compareToIgnoreCase(names[m]) == 0){
								//found the bot. skip this name
								found = true;
								break;
							}
						}
						else {
							//yes, this seat is empty.
							if (emptyPos == -1)
								emptyPos = n;
						}
					}
					
					_cat.debug("seat found is : " + emptyPos + " and found is : " + found);
					
					if (found)
						continue;
					
					//now check if the table can accommodate this bot
					if (emptyPos == -1)
						continue;//no space on table for this bot
					
					Presence bp = GameProcessor.handleBot(names[m], _gid, gp._handler);
					
					bp.isBot = true;
					bp.sid = Rng.getNewSessionId();
					
					//now create a rummy profile and make this player sit on table
					RummyProfile kp = new RummyProfile();
					kp.setName(bp.name());
					
					//giving bot only 1000 as chips
					kp.setGameStartWorth(1000);
					_cat.debug("player got " + kp.getGameStartWorth());
					
					kp.setPresence(bp);
					kp.setRUMMYStatus(0);
					kp.rummyLastMoveTime = System.currentTimeMillis();
					
					kp.setPos(emptyPos);
					kp.setRummyPlayer();
					
					addPresence(kp);
					
					countHandsBotOnly = 0;
					
					if (_gameOngoing) {
//						fixingDealerNextHand = true;
						kp.setRUMMYStatus(status_JOINED);
					}
					else {
						kp.setRUMMYStatus(status_JOINED);
					}
				}
				
				return;
			}

			if (_players[pos] == null || _players[pos].getPresence() == null) {
				_cat.debug("wrong wrong wrong rummy profile or presence missing");
				return;
			}
			
			if (moveId == move_RUMMY_CHAT) {
				_lastMove = move_RUMMY_CHAT;
				_lastMovePos = pos;
				_lastMoveString = "Chat";
				chatOn = 1;
				chatMessage = "Pos " + pos + ":" + cardsDet;
				broadcastMessage(-1);
				chatOn = 0;
				chatMessage = "";
				return;
			}
			
			if (moveId == move_RUMMY_SITOUT) {
				_lastMove = move_RUMMY_SITOUT;
				_lastMovePos = pos;
				_lastMoveString = "SitOutNextGame";
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + ": wants to sitout next game.";
				
				if (isSittingOut(_players[pos])){
					_cat.debug("already sitting out");
					return;
				}
				
				if (!sitoutPlayers.contains(pos))
					sitoutPlayers.add(pos);
				
				if (!_gameOngoing)
					handleSitoutSitin();
				
				broadcastMessage(-1);
				chatMessage = "";
				return;
			}
			
			if (moveId == move_RUMMY_SITIN) {
				_lastMove = move_RUMMY_SITIN;
				_lastMovePos = pos;
				_lastMoveString = "RejoinNextGame";
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + ": wants to rejoin next game.";
				
				if (!isSittingOut(_players[pos])){
					_cat.debug("not sitting out so why?");
					return;
				}
				
				if (!sitinPlayers.contains(pos))
					sitinPlayers.add(pos);
				
				if (!_gameOngoing)
					handleSitoutSitin();
				
				broadcastMessage(-1);
				chatMessage = "";
				return;
			}

			if (!_gameOngoing) {
				_cat.debug("game not running, ignore all messages - let them all die!!!!!");
				return;
			}

			RummyProfile prp = _players[pos];

			newCardAdded = "";
			if (_nextMovePlayer == 111 && moveId != move_RUMMY_DECLARE_LOSER) {
				_cat.debug("no moves other than declare loser allowed!!!");
				sendErrorMessage(pos, 3);
				return;
			} else {
				if (_nextMovePlayer != 111 && pos != _nextMovePlayer
						&& moveId != move_RUMMY_DECLARE) {
					_cat.debug("no moves allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
				if ((moveId & _nextMovesAllowed) != moveId
						&& _nextMovePlayer != 111) {
					_cat.debug("these moves not allowed from this pos!!!");
					sendErrorMessage(pos, 0);
					return;
				}
			}

			_players[pos].rummyLeftTableTime = -1;

			// update the rummyLastMoveTime for this player
			_players[pos].rummyLastMoveTime = System.currentTimeMillis();

			if (moveId == move_RUMMY_DECLARE_LOSER) {
				//error condition. if by chance declare loser message comes after
				//winner declaration, the response overwrites the winner message
				//clients don't see result windows
				if (!_winnerString.isEmpty()){
					//winner declaration done. don't send any message.
					return;
				}
				
				chatOn = 0;
				chatMessage = "Pos : " + (prp.pos() + 1) + " declared";
				
				_lastMove = move_RUMMY_DECLARE_LOSER;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclareLoser";
				prp.setRUMMYMovesStr("&GameDeclareLoser^" + cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_DECLARE_LOSER);

				// just store the cards arrangement string from player
				prp.cardsStrFromClient = cardsDet;

				countPlayerResponseDeclareLoser--;
				if (countPlayerResponseDeclareLoser <= 0) {
					// end the game here
					_winnerString = declareGameOver(idPlayerValidDeclared);
					broadcastMessage(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					_nextMovePlayer = -1;
				}
			}

			if (moveId == move_RUMMY_DECLARE) {
				_cat.debug("player declares!!!!!!!!!!!!!!!!!");
				_lastMove = move_RUMMY_DECLARE;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclare'";
				prp.setRUMMYMovesStr("&GameDeclare^" + cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_DECLARE);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);

				int typeIndex = -1;
				// special case : if some player does a declare when it is not
				// his turn, he has to sent type as -1
				if (type.compareToIgnoreCase("-1") != 0) {
					// it is not -1, so it is a valid card being discarded
					if (type.compareToIgnoreCase("JO") == 0) {
						typeIndex = 161;
					} else if (type.compareToIgnoreCase("JO1") == 0) {
						typeIndex = 162;
					} else if (type.compareToIgnoreCase("JO2") == 0) {
						typeIndex = 163;
					} else {
						Card ctemp = new Card(type);
						typeIndex = ctemp.getIndex();
						if (typeIndex == -1) {
							sendErrorMessage(pos, 2);
							return;
						}
					}
					// get rid of card - index is in type
					int[] newCards = new int[13];
					int j = 0;
					boolean found = false;
					for (int i = 0; i < 14; i++) {
						if (prp._allotedCards[i].getIndex() != typeIndex
								|| found) {
							newCards[j++] = prp._allotedCards[i].getIndex();
						} else {
							found = true;
						}
					}
					prp._allotedCards = new Card[13];
					for (int i = 0; i < newCards.length; i++) {
						Card crd = new Card(newCards[i]);
						crd.setIsOpened(true);
						prp._allotedCards[i] = crd;
						// _cat.debug("card added : " + crd.toString());
					}
				} else {
					// check if teh player is deliberately sending declare move
					if ((prp.getRUMMYStatus() & status_ACTIVE) != status_ACTIVE) {
						return;
					}
				}

				// check if cards are indeed valid
				boolean flagGameOver = false;
				if (prp._markCheckingCards)
					return;

				prp._markCheckingCards = true;
				idPlayerChecked = pos;
				prp.cardsStrFromClient = cardsDet;
				int[] result = checkValidCards2(prp._allotedCards,
						prp.cardsStrFromClient);
				prp._markCheckingCards = false; // has to be done because
												// checkValidCards takes time to
												// complete and we don't want
												// multiple calls
				_cat.debug("penalyt : " + result[0] + ", first life : "
						+ result[1] + " , 2nd life : " + result[2]);
				
				//always discard card, even if it is a failed bid to declare
				_discardCard = new Card(typeIndex);
				_discardCard.setIsOpened(false);//16th Nov 17 as per PIR reqmts
				_discardDeck[_indexDiscardDeck++] = _discardCard;
				
				if (result[0] == 0 && result[1] == 1)
					flagGameOver = true;

				if (flagGameOver) {
					chatOn = 0;
					chatMessage = "Pos : " + (prp.pos() + 1) + " made valid declaration.";
					_lastMoveString += "Valid:Winner";
					prp.setRUMMYMovesStr("^ValidDeclare");
					idPlayerValidDeclared = pos;
					// for each player, now set the move as declare loser. now
					// keep a count of players from whom messages have to come
					_nextMovesAllowed = move_RUMMY_DECLARE_LOSER;
					_nextMoveExpTime = System.currentTimeMillis();
					_nextMovePlayer = 111;
					broadcastMessage(-1);

					// now keep a track of how many active players are going to
					// respond back
					for (int m = 0; m < MAX_PLAYERS; m++) {
						if (_players[m] != null
								&& m != pos
								&& (isActive(_players[m])
										&& !isRemoved(_players[m]) && !isFolded(_players[m]))) {
							// only non folded players are supposed to make
							// moves
							countPlayerResponseDeclareLoser++;
						}
					}

					if (countPlayerResponseDeclareLoser == 0) {
						// no other player to wait on - end this drama now.
						_winnerString = declareGameOver(idPlayerValidDeclared);
						broadcastMessage(idPlayerValidDeclared);
						idPlayerValidDeclared = -1;
						_nextMovePlayer = -1;
					}

					return;
				} else {
					// apply penalty on player, fold him, continue game
					chatOn = 0;
					chatMessage = "Pos : " + (prp.pos() + 1) + "made invalid declaration.";
					_lastMoveString += "Invalid:Folded";
					prp.setRUMMYMovesStr("^ValidFail");
					prp.setRUMMYStatus(status_FOLDED);
					prp._allotedCards = null;
					prp.setRUMMYPoints(80);
					if (!checkGameOver()) {
						if (pos == _nextMovePlayer) {
							_nextMovesAllowed = move_RUMMY_DECLARE
									| move_RUMMY_NEWCARD | move_RUMMY_FOLD;
							_nextMovePlayer = getRightOfPlayer(_nextMovePlayer);
							_nextMoveExpTime = System.currentTimeMillis();
						}
						broadcastMessage(-1);
					}
				}
			}

			// if (moveId == move_RUMMY_RUMMYCUT) {
			// //this move can only come from designated player rummyPlayer, no
			// one else can send it
			// _lastMove = move_RUMMY_NEWCARD;
			// _lastMovePos = pos;
			// _lastMoveString = "RummyCut";
			// prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
			// prp.setRUMMYStatus(status_ACTIVE);
			// _nextMoveExpTime = System.currentTimeMillis();
			//
			// //give cards to all players
			// for (int m = 0; m < MAX_PLAYERS; m++) {
			// if (_players[m] != null && _players[m].getPresence() != null) {
			// for (int i = 0; i < 13; i++) {
			// int randCard = drawCard();
			// Card cr = new Card(randCard);
			// cr.setIsOpened(true);
			// _players[m]._allotedCards[i] = cr;
			// }
			// }
			// }
			//
			// //set the next move player - it will be the one on dealer's left
			// _nextMovePlayer = getNextActivePos(_dealer);
			// _nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD |
			// move_RUMMY_FOLD;
			// broadcastMessage(-1);
			// }

			if (moveId == move_RUMMY_NEWCARD) {
				_cat.debug("palyer wants new card");
				int typeO = 0;

				try {
					typeO = Integer.parseInt(type);
				} catch (NumberFormatException ex) {
					_cat.debug("wrong string for type!!!!!");
					sendErrorMessage(pos, 1);
					return;
				}

				_cat.debug("reached here 3333333333333333333333333 "
						+ typeO + " , cards length : "
						+ prp._allotedCards.length);

				// if client sends this message again then 2nd and subsequent
				// message have to be dropped
				if (prp._allotedCards.length >= 14) {
					_cat.debug("already gave one card, how many does it want more !!!");
					sendErrorMessage(pos, 0);
					return;
				}

				// use type to find if it is frsh pile card (0) or discard (1)
				Card cr = null;
				if (typeO == 0) {
					_lastMoveString = "ChoseFreshCard";
					int randCard = drawCard();
					cr = new Card(randCard);
					cr.setIsOpened(true);
					prp.setRUMMYMovesStr("&GetFresh^" + cr.toString());
					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos : " + (prp.pos() + 1) + " chose fresh card.";
				} else {
					cr = new Card(_discardCard.getIndex());
					cr.setIsOpened(true);
					
					//added on 20th Sep 2017 - 
					//joker card in discarded cards can't be taken
					if (determineIfJoker(cr)){
						_cat.debug("can't take joker card from discards...");

						//check now if this is the very first move of very first player
						//then it is allowed
						boolean firstMove = true;
						for (int k = 0; k < MAX_PLAYERS; k++){
							if (_players[k] != null && _players[k]._firstMoveDone)
								firstMove = false;
						}
						
						if (!firstMove) {
							sendErrorMessage(pos, 4);
							return;
						}
					}
					
					_lastMoveString = "ChoseDiscardedCard";
					prp.setRUMMYMovesStr("&GetDisc^" + _discardCard.toString());
					
					_discardDeck[_indexDiscardDeck - 1] = null;
					_indexDiscardDeck--;
					if (_indexDiscardDeck > 0)
						_discardCard = _discardDeck[_indexDiscardDeck - 1];
					else
						_discardCard = null;

					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos : " + (prp.pos() + 1) + " chose discarded card";
				}

				_cat.debug("came here : " + _lastMoveString
						+ " , card : " + cr.toString());
				
				prp._firstMoveDone = true;
//				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_NEWCARD;
				_lastMovePos = pos;
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
				prp.setRUMMYStatus(status_ACTIVE);

				Card[] clonedCards = prp._allotedCards.clone();
				prp._allotedCards = new Card[14];
				for (int i = 0; i < 13; i++) {
					prp._allotedCards[i] = clonedCards[i];
				}
				prp._allotedCards[13] = cr;

				// no need to change player
				if (!checkGameOver()) {
					_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_DISCARD;
					_nextMoveExpTime = System.currentTimeMillis();
					broadcastMessage(-1);
				}
			}

			if (moveId == move_RUMMY_DISCARD) {
				_cat.debug("player discards...");
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_DISCARD;
				_lastMovePos = pos;
				_lastMoveString = "DiscardCard:" + type;
				prp.setRUMMYStatus(status_ACTIVE);
				prp.setRUMMYMoveId(move_RUMMY_DISCARD);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				int typeIndex = -1;

				if (type.compareToIgnoreCase("JO") == 0) {
					typeIndex = 161;
				} else if (type.compareToIgnoreCase("JO1") == 0) {
					typeIndex = 162;
				} else if (type.compareToIgnoreCase("JO2") == 0) {
					typeIndex = 163;
				} else {
					Card ctemp = new Card(type);
					typeIndex = ctemp.getIndex();
					if (typeIndex == -1) {
						sendErrorMessage(pos, 2);
						return;
					}
				}
				
				// get rid of card - index is in type
				// get rid of card - index is in type
				int[] newCards = new int[13];
				int j = 0;
				boolean found = false;
				for (int i = 0; i < 14; i++) {
					if (prp._allotedCards[i].getIndex() != typeIndex || found) {
						newCards[j++] = prp._allotedCards[i].getIndex();
					} else {
						found = true;// found 1st instance of this card being
										// removed. if there are 2 cards and
										// player wants to get rid of 1, then
										// other should stay.
					}
				}
				prp._allotedCards = new Card[13];
				for (int i = 0; i < newCards.length; i++) {
					Card crd = new Card(newCards[i]);
					crd.setIsOpened(true);
					prp._allotedCards[i] = crd;
					// _cat.debug("card added : " + crd.toString());
				}

				_discardCard = new Card(typeIndex);
				_discardCard.setIsOpened(true);
				_discardDeck[_indexDiscardDeck++] = _discardCard;
				
				chatOn = 0;
				chatMessage = "Pos : " + (prp.pos() + 1) + " discarded " + _discardCard.toString();
				
				//remember the card discarded
				prp.setRUMMYMovesStr("&Discard");
				if (typeIndex >= 161)
					prp.setRUMMYMovesStr("^JO");
				else
					prp.setRUMMYMovesStr("^" + _discardCard.toString());

				if (!checkGameOver()) {
					_nextMovePlayer = getRightOfPlayer(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
							| move_RUMMY_FOLD;
					broadcastMessage(-1);
				}
			}

			if (moveId == move_RUMMY_FOLD) {
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_FOLD;
				_lastMovePos = pos;
				_lastMoveString = "Folded";
				prp.setRUMMYMovesStr("&Pack");
				prp.setRUMMYStatus(status_FOLDED);
				prp.setRUMMYMoveId(move_RUMMY_FOLD);
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				chatOn = 0;
				chatMessage = "Pos : " + (prp.pos() + 1) + " folded.";

				if (prp._firstMoveDone)
					prp.setRUMMYPoints(40);
				else {
					prp.setRUMMYPoints(20);
					// player didn't even play one card, so put his cards in
					// fresh pile
					addCardsToFreshPile(prp._allotedCards, 13);
				}

				if (!checkGameOver()) {
					// game not over
					_nextMovePlayer = getRightOfPlayer(_nextMovePlayer);
					_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
							| move_RUMMY_FOLD;
					broadcastMessage(-1);
				}
			}

		}

		public void fixDealerFirst() {
			if (_gameOngoing)
				return;

			// now clear out the all in players list
			resetTable();
			
			_lastMovePos = -1;
			_lastMove = 0;
			_lastMoveString = "";
			
			_cardDealingOrder = "";
			
			_gameOngoing = true;

			if (true) {

				_deck.clear();
				fixingDealerOngoing = true;
				
				counterGameNotStarted = 0;

				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null && isActive(_players[m])) {
						_players[m].fixPosCard = drawCardOneDeck();
					}
				}

				int least = 999, leastCardBearer = -1;
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null && isActive(_players[m])) {
						if (_players[m].fixPosCard % 13 < least) {
							least = _players[m].fixPosCard % 13;
							leastCardBearer = m;
						} else if (_players[m].fixPosCard % 13 == least) {
							// if the ranks are same, then check for suits
							if (leastCardBearer != -1) {
								if (_players[m].fixPosCard < _players[leastCardBearer].fixPosCard) {
									leastCardBearer = m;
								}
							}
						}
					}
				}

				_dealer = leastCardBearer;
				_rummyPlayer = getRightOfPlayer(_dealer);
				_nextMovePlayer = -1;
				broadcastMessage(-1);

				// now start the game - have to wait for some seconds to give
				// time to client to display the cards and the new dealer and
				// rummy player
				// based on sitting players, let us see if we can start a game
				try {
					Thread.currentThread().sleep(2000);
				} catch (InterruptedException ee) {
					// continue
				}
				fixingDealerOngoing = false;
//				fixingDealerNextHand = false;
//				countHandsAfterDealerFix = 0;
			} 
//			else {
//				countHandsAfterDealerFix++;
//				if (countHandsAfterDealerFix >= 3)
//					fixingDealerNextHand = true;
//			}

			startGameIfPossible();
		}

		public void startGameIfPossible() {
			// based on sitting players, let us see if we can start a game
//			try {
//				Thread.currentThread().sleep(1000);
//			} catch (InterruptedException ee) {
//				// continue
//			}

			// game can begin ---
			// now clear out the all in players list
			// resetTable();

			int _countPlayersInit = getCountActivePlayers();
			_cat.debug("startgame - " + _countPlayersInit);
			if (_countPlayersInit >= 2) {
				toSendMsg = false;
				
				_amtWonString = "";

				if (_countPlayersInit > 4)
					NUM_DECKS = 3;
				else if (_countPlayersInit > 2)
					NUM_DECKS = 2;
				else
					NUM_DECKS = 1;

				MAX_CARDS = 52 * NUM_DECKS - 1;

				initGame();

				// now initialize the variables
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& !isSittingOut(_players[m])
					// && _players[m].getPresence() != null
					) {
						_players[m]._firstMoveDone = false;
						_players[m]._allotedCards = new Card[13];
						_players[m].setRUMMYWinAmt(0);
						_players[m].setRUMMYStatus(status_ACTIVE);
					}
				}

				idPlayerValidDeclared = -1;
				idPlayerChecked = -1;

				// now set teh _cardDealingOrder starting from right of dealer -
				// haha - right means decrement by 1
				// but first check if dealer is still pointing to a valid player
				// - if not, shift it now
				if (_dealer == -1)
					_dealer = 0;
				if (_players[_dealer] == null || isRemoved(_players[_dealer])
						|| isLeft(_players[_dealer])) {
					getNextActivePos(_dealer);
				}

				_rummyPlayer = getRightOfPlayer(_dealer);

				for (int m = 0; m < MAX_PLAYERS; m++) {
					int index = _dealer - (m + 1);
					if (index < 0)
						index = 5 + index + 1;
					if (_players[index] != null
							&& _players[index].getPresence() != null
							&& isActive(_players[index]))
						_cardDealingOrder += index + "'";
				}
				_cardDealingOrder = _cardDealingOrder.substring(0,
						_cardDealingOrder.length() - 1);

				lastRespTime = System.currentTimeMillis();

				_winnerString = "";
				rummygrid = setNextGameRunId();
				// _nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD |
				// move_RUMMY_FOLD;
				// _nextMovesAllowed = move_RUMMY_RUMMYCUT;
				_nextMovesAllowed = move_RUMMY_DECLARE | move_RUMMY_NEWCARD
						| move_RUMMY_FOLD;

				// give cards to all players
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null && !isSittingOut(_players[m])) {
						//dealer, rummy joker card, points per rupee, table id
						_players[m].setRUMMYMovesStr("&TID^" + _tid);
						_players[m].setRUMMYMovesStr("&Dealer^" + _dealer);
						_players[m].setRUMMYMovesStr("&RummyCard^" + _rummyCardJoker.toString());
						_players[m].setRUMMYMovesStr("&PtsPerRupee^" + POINTSPERRUPEE);
						
						_players[m]._allotedCards = new Card[13];// always clear
																	// the hand
																	// here

						//player got cards. store it in db
						_players[m].setRUMMYMovesStr("&Cards^");
						for (int i = 0; i < 13; i++) {
							int randCard = drawCard();
							Card cr = new Card(randCard);
							cr.setIsOpened(true);
							_players[m]._allotedCards[i] = cr;
							
							_players[m].setRUMMYMovesStr(cr.toString());
							if (i < 12)
								_players[m].setRUMMYMovesStr("`");
						}
						
					}
				}

				_nextMovePlayer = -1;
				broadcastMessage(-1);
				// sleep for 10 seconds to allow clients to distribute cards
				try {
					Thread.currentThread().sleep(15000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				_nextMoveExpTime = System.currentTimeMillis();
				_gameStartTime = Calendar.getInstance();
				_nextMovePlayer = _rummyPlayer;
				broadcastMessage(-1);
			} else {
				_gameOngoing = false;
				_dealer = -1;
				_rummyPlayer = -1;
				// for the unfortunate player who might be seated on teh table
				if (_countPlayersInit == 1)
					broadcastMessage(-1);
			}
		}
		
		public String encrypt(String text){
			String key = "DealServer123456"; // 128 bit key
			Key aesKey = new SecretKeySpec(key.getBytes(), "AES");
			_cat.debug("aeskey got " + aesKey);
			try {
	            Cipher cipher = Cipher.getInstance("AES");
	            _cat.debug("cipher opbtained ... " + cipher);
	            // encrypt the text
	            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
	            byte[] encrypted = cipher.doFinal(text.getBytes());
//	            String str = new String(encrypted, "ISO-8859-1");
	            String str = Base64.encode(encrypted);
	            _cat.debug("encryped : " + str);
	            return str;
			}
			catch(Exception ex){
				_cat.debug("exception : " + ex.getMessage());
			}
			
			return null;
		}

		public void broadcastMessage(int winnerPos) {
			_cat.debug("broadcasting response!!!");
			StringBuffer temp = new StringBuffer();
			temp.append("RummyServer=RummyServer");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);
			
			if (!_gameOngoing){
				double to = 8000 - (System.currentTimeMillis() - _nextMoveExpTime);
				if (to < 0)
					to = 0;
				int toint = (int)(to/1000);
				temp.append(",TimerNG=").append(toint);
			}
			
			if (_nextMovePlayer != -1 && _nextMovePlayer < MAX_PLAYERS && _players[_nextMovePlayer] != null && winnerPos == -1){
				//for time bank
				if (_players[_nextMovePlayer].getUsingTimeBank()){
					temp.append(",DiscProtOn=").append((_players[_nextMovePlayer].getTimeBank()) * 10);
				}
			}

			if (_rummyCardJoker != null)
				temp.append(",RummyJoker=").append(_rummyCardJoker.toString());
			else
				temp.append(",RummyJoker=");
			if (_discardCard != null && idPlayerValidDeclared == -1)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");
			
			temp.append(",chatMsgOn=").append(chatOn);
			if (!chatMessage.isEmpty()){
				String strcrypt = encrypt(chatMessage);
				temp.append(",chatMsg=").append(strcrypt);
//				_cat.debug(decrypt(strcrypt));
				chatMessage = "";
			}
			else
				temp.append(",chatMsg=");

			if (idPlayerValidDeclared != -1) {
				temp.append(",ValidDecPlyr=").append(
						_players[idPlayerValidDeclared].name);
				temp.append(",ValidDecPlyrId=").append(
						idPlayerValidDeclared);
			}

			temp.append(",NextMovePos=").append(_nextMovePlayer);
			temp.append(",NextMoveId=").append(_nextMovesAllowed);
			temp.append(",LastMove=").append(_lastMove);
			temp.append(",LastMovePos=").append(_lastMovePos);
			temp.append(",LastMoveType=").append(_lastMoveString);

			temp.append(",DealingOrder=").append(_cardDealingOrder);

			// add the bit about checking cards of players
			if (fixingDealerOngoing) {
				String str = "";
				for (int i = 0; i < MAX_PLAYERS; i++) {
					if (_players[i] != null && isActive(_players[i])) {
						Card tempCr = new Card(_players[i].fixPosCard);
						str += i + "`" + tempCr.toString() + "'";
					}
				}
				if (!str.isEmpty())
					str = str.substring(0, str.length() - 1);
				
				temp.append(",FixDealerProcess=").append(1);
				temp.append(",FixDealerCards=").append(str);
			}

			if (winnerPos != -1) {
				temp.append(",Winner=").append(winnerPos);
				if (_players[winnerPos] != null && _players[winnerPos]._allotedCards != null){
					String str = "";
					for (int k = 0; k < _players[winnerPos]._allotedCards.length; k++)
						str += _players[winnerPos]._allotedCards[k].toString()
								+ "'";
					str = str.substring(0, str.length() - 1);
	
					temp.append(",WinnerCards=").append(str);
					if (!_players[winnerPos].cardsStrFromClient.isEmpty())
						temp.append(",WinnerCardsString=").append(
								_players[winnerPos].cardsStrFromClient);
					
					temp.append(",WinPoints=").append(
							_players[winnerPos].getRUMMYWinAmt());
				}

				temp.append(",Penalty=").append(_winnerString);
				temp.append(",AmountsWon=").append(_amtWonString);
			}
			// now create that amalgam of all players status, pos, chips
			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":"
							+ (!_players[i].getPresence().isBot ? _players[i].getPresence().getAmtAtTable() : _players[i].getGameStartWorth()));
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

			_cat.debug("reaching here : temp : " + temp);

			// this temp can be now sent to all observers on the table
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				// if (!pro.isRummyPlayer()) {
				_cat.debug("sending to pro : " + pro.getName());
				sendMessage(temp, pro);
				// }
			}
			// for each presence, call sendMessage with their individual data
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					if(!_players[i].getPresence().isBot || i == _nextMovePlayer) {
						StringBuffer tempPlayerData = new StringBuffer(temp);
						tempPlayerData.append(",PlayerPos=").append(i);
						tempPlayerData.append(",PlayerName=").append(_players[i].name);
						if (_players[i]._allotedCards != null
								&& !fixingDealerOngoing) {
							String str = "";
							for (int k = 0; k < _players[i]._allotedCards.length; k++)
								str += _players[i]._allotedCards[k].toString()
										+ "'";
							str = str.substring(0, str.length() - 1);
							tempPlayerData.append(",Cards=" + str);
						} else
							tempPlayerData.append(",Cards=");

						tempPlayerData.append(",NewCardAdded=" + newCardAdded);
						sendMessage(tempPlayerData, _players[i]);
					}
				}
			}
		}

		public void sendMessage(StringBuffer resp, RummyProfile pr) {
			Game g = Game.game(_gid);
			Casino cg = (Casino) g;
			com.hongkong.game.resp.Response r = (com.hongkong.game.resp.Response) new MoveResponse(
					cg, resp, pr.getPresence());
			try {
				GameProcessor.deliverResponse(r);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		
		public void sendMessageKickedOut(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("RummyServer=RummyServer");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);
			temp.append(",KickedOut=").append(prpos);

			// for reason
			if (resCode == 0) {
				temp.append(",KickReason=").append("Player left!");
			} else if (resCode == 1) {
				temp.append(",KickReason=").append(
						"Didn't play in last 5 hands!");
			} else if (resCode == 2) {
				temp.append(",KickReason=").append("No action for long!");
			}

			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":"
							+ _players[i].getPresence().getAmtAtTable());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);
			
			_cat.debug("temp from kick out : " + temp);
			
			// this temp can be now sent to all observers on the table
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				if (!pro.isRummyPlayer()) {
					sendMessage(temp, pro);
				}
			}
			// for each presence, call sendMessage with their individual data
		//	for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[prpos] != null) {
					StringBuffer tempPlayerData = new StringBuffer(temp);
					tempPlayerData.append(",PlayerPos=").append(prpos);
					sendMessage(tempPlayerData, _players[prpos]);
				}
		//	}
		}

		public void sendErrorMessage(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("RummyServer=RummyServer");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);

			// for reason
			if (resCode == 0) {
				temp.append(",MsgDropped=").append("WrongMove");
			} else if (resCode == 1) {
				temp.append(",MsgDropped=").append("WrongCard");
			} else if (resCode == 3) {
				temp.append(",MsgDropped=").append("whoareyoukidding");
			} else if (resCode == 4) {
				temp.append(",MsgDropped=").append("CantTakeDiscardedJoker");
			} else if (resCode == 11) {
				temp.append(",PenalCards=" + globalPenal + "'firstLife="
						+ globalFirstLife + "'secondLife=" + globalSecondLife);
			} else {
				temp.append(",MsgDropped=Wrong");
			}

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);

			if (_rummyCardJoker != null)
				temp.append(",RummyJoker=").append(_rummyCardJoker.toString());
			else
				temp.append(",RummyJoker=");
			if (_discardCard != null && idPlayerValidDeclared == -1)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");

			temp.append(",NextMovePos=").append(_nextMovePlayer);
			temp.append(",NextMoveId=").append(_nextMovesAllowed);
			temp.append(",LastMove=").append(_lastMove);
			temp.append(",LastMovePos=").append(_lastMovePos);
			temp.append(",LastMoveType=").append(_lastMoveString);

			temp.append(",DealingOrder=").append(_cardDealingOrder);

			// add the bit about checking cards of players
			// now create that amalgam of all players status, pos, chips
			StringBuffer tempPlayerDet = new StringBuffer();
			int nCount = 0;
			for (int i = 0; i < MAX_PLAYERS; i++) {
				if (_players[i] != null) {
					nCount++;
					tempPlayerDet.append("'" + i + ":" + _players[i].getName()
							+ ":");
					tempPlayerDet.append(_players[i].getRUMMYStatus() + ":"
							+ _players[i].getPresence().getAmtAtTable());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

			// for each presence, call sendMessage with their individual data
			int i = prpos;
			if (_players[i] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(i);
				if (_players[i]._allotedCards != null && !fixingDealerOngoing) {
					String str = "";
					for (int k = 0; k < _players[i]._allotedCards.length; k++)
						str += _players[i]._allotedCards[k].toString() + "'";
					str = str.substring(0, str.length() - 1);
					tempPlayerData.append(",Cards=" + str);
				} else
					tempPlayerData.append(",Cards=");

				tempPlayerData.append(",NewCardAdded=" + newCardAdded);
				sendMessage(tempPlayerData, _players[i]);
			}
		}
	}

	private int getTID(String movedet) {

		String[] betString = movedet.split(",");
		String tid = betString[0];
		return Integer.parseInt(tid);
	}

	private int getPos(String movedet) {

		String[] betString = movedet.split(",");
		String pos = betString[1];
		return Integer.parseInt(pos);
	}

	private int getMoveId(String movedet) {

		String[] betString = movedet.split(",");
		String moveid = betString[2];
		return Integer.parseInt(moveid);
	}

	// private double getAmt(String movedet) {
	//
	// String[] betString = movedet.split(",");
	// String amt = betString[3];
	// return Double.parseDouble(amt);
	// }

	private String getType(String movedet) {

		String[] betString = movedet.split(",");
		String amt = betString[3];
		return amt;
	}

	// getCardsDet
	private String getCardsDet(String movedet) {

		String[] betString = movedet.split(",");
		String amt = betString[4];
		return amt;
	}

	public StringBuffer processMove(Player.Presence p, String movedet) {
		// deliberately keeping it empty, logic moved to rummyMove()
		return null;
	}

	public int findNextInValidTable() {
		for (int i = 0; i < _tables.length; i++) {
			if (!_tables[i].validTable)
				return i;
		}
		return -1;
	}

	// rummyReload
	public StringBuffer rummyReload(Player.Presence p, String movedet) {
		StringBuffer buf = new StringBuffer();

		int tid = getTID(movedet);// table id
		if (tid >= MAX_TABLES || tid < 0 || !_tables[tid].validTable) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int pos = getPos(movedet);
		if (pos < 0 || pos > MAX_PLAYERS) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidPos,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		_tables[tid]._players[pos].setReloadReq(true);
		buf.append("RummyServer=RummyServer,grid=").append(p.getGRID())
				.append(",player-details=" + p.name() + "|" + p.netWorth());
		return buf;
	}
	
	private StringBuffer handleReqPvtTable(Player.Presence p){
		StringBuffer buf = new StringBuffer();
		
		int availPvtTable = -1;
		//try to find an empty private table
		for (int i = MAX_OPEN_TABLES; i < MAX_TABLES; i++){
			if (_tables[i].getCountTotalPlayers() == 0){
				//we found it. yay!
				availPvtTable = i;
			}
		}
		
		if (availPvtTable >= MAX_TABLES || availPvtTable == -1){
			buf.append("RummyServer=RummyServer,grid=")
			.append(-1)
			.append(",TID=999")
			.append(",MsgDropped=TablesFull,player-details="
					+ p.name() + "|" + p.netWorth());
			return buf;
		}
		
		if (p.getAmtAtTable() < 80 * _tables[availPvtTable].POINTSPERRUPEE) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",TID=999")
					.append(",MsgDropped=PlayerBroke,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}
		
		int codeGenerated = 5001 * availPvtTable;
		_cat.debug("availpvttable : " + availPvtTable);
		
		// create a new rummyprofile for this presence
		RummyProfile kp = new RummyProfile();
		kp.setName(p.name());
		kp.setGameStartWorth(p.getAmtAtTable());
		kp.setPresence(p);
		p.setKPIndex(_tables[availPvtTable].addObserver(kp));
		kp.setRUMMYStatus(0);
		kp.rummyLastMoveTime = System.currentTimeMillis();
		buf.append("RummyServer=RummyServer,grid=")
				.append(-1)
				.append(",TID=").append(availPvtTable)
				.append(",PvtTblCode=").append(codeGenerated)
				.append(",player-details=" + p.name() + "|"
						+ p.netWorth());

		return buf;
	}
	
	public void sendMessage(StringBuffer resp, Presence pr) {
		Game g = Game.game(_gid);
		Casino cg = (Casino) g;
		com.hongkong.game.resp.Response r = (com.hongkong.game.resp.Response) new MoveResponse(
				cg, resp, pr);
		try {
			GameProcessor.deliverResponse(r);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	public StringBuffer rummySitIn(Player.Presence p, String movedet) {
		// 1,-1,0,99915.35
		int tid = getTID(movedet);// table id, which table he wants to be seated
		int pos = getPos(movedet);// which chair position he wants to be seated
		int moveId = getMoveId(movedet);
		// double amt = getAmt(movedet);
		String type = getType(movedet);
		String cardsDet = getCardsDet(movedet);

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("RummyServer game - sit in req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			return null;
		}
		
		//special case for private table
		if (tid == 999){
			//request for private table came
			return handleReqPvtTable(p);
		}
		
		if (tid >= MAX_TABLES || tid < 0 || !_tables[tid].validTable) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}
		
		//private table. check if player has code
		//pos field will contain the code
		if (tid >= MAX_OPEN_TABLES){
			//code for private tables.
			//new player has to send approved code
			//first player has to send code to invited players
			//use type to differentiate b/w these 2 cases
			int moveType = Integer.parseInt(type);
			if (moveType == 0){
				_cat.debug("invite request : " + cardsDet);
				//send invite request
				//cardsDet variable holds the userids separated by '
				String[] toks = cardsDet.split("\\'");
				//these userids have to be online to receive invite req
				for (int i = 0; i < toks.length; i++){
					StringBuffer buf2 = new StringBuffer();
					buf2.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",URInvited=1")
					.append(",PvtTblCode=").append(50 * tid);
					
					_cat.debug("invited player : " + toks[i]);
					
					if (_tables[tid].getCountTotalPlayers() <= 1)
						buf2.append(",player-details=" + p.name() + "|"
							+ p.netWorth());
					else {
						String str = "";
						for (int m = 0; m < _tables[tid]._players.length; m++){
							if (_tables[tid]._players[m] != null && _tables[tid]._players[m].getPresence() != null)
								str += _tables[tid]._players[m].getName() + "|" + _tables[tid]._players[m].getPresence().netWorth() + "`";
						}
						str = str.substring(0, str.length() - 1);
						
						buf2.append(",player-details=" + str);
					}
					
					boolean flag = false;
					
					//if table is already full, drop invitations...
					if (_tables[tid].getCountTotalPlayers() < MAX_PLAYERS) {
						GamePlayer gp = GamePlayer.getPlayer(toks[i]);
						Enumeration en = gp.getPresenceList();
						while(en.hasMoreElements()){
							Presence pr = (Presence) en.nextElement();
							if (pr.getGID() == _gid){
								//found the one loitering in lobby. he is there, isn't he?
								sendMessage(buf2, pr);
								flag = true;
								_cat.debug("sending message to player : " + pr.name());
							}
						}
						
						if (!flag){
							//player not loitering in the rummy lobby area.
							//store the message with gameprocessor
							//to be sent the moment player decides to sit on rummy table
							//TBD...
							GameProcessor.saveMessageForRummyInvite(_gid, buf2.toString(), toks[i]);
						}
					}
				}

				//send success message to sending player
				buf.append("RummyServer=RummyServer,grid=")
				.append(-1)
				.append(",TID=").append(tid)
				.append(",InvitesSent=1");
				
				if (_tables[tid].getCountTotalPlayers() <= 1)
					buf.append(",player-details=" + p.name() + "|"
						+ p.netWorth());
				else {
					String str = "";
					for (int m = 0; m < _tables[tid]._players.length; m++){
						str += _tables[tid]._players[m].getName() + "|" + _tables[tid]._players[m].getPresence().netWorth() + "`";
					}
					str = str.substring(0, str.length() - 1);
					
					buf.append(",player-details=" + str);
				}

				return buf;
			}
			else if (moveType > 1) {
				_cat.debug("invited player, pos : " + pos);
				//check if code is equal
				if (pos != 50 * tid){
					buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=InvalidCode,player-details="
							+ p.name() + "|" + p.netWorth());
					return buf;
				}
				//let it fall below. we need that code to be executed.
			}
			
			//the case where moveType = 1 is for player who got the pvt table open
			//he now wants to sit on table.
		}

		int origPos = -1;
		origPos = _tables[tid].findPos(p);
		if (moveId == 0) {
			// just wants to observe, it is ok
			// put a conditon over here for the plaeyrs who want to come back
			// they had placed some bets and are still valid players on the
			// table
			if (origPos != -1) {
				// found him, seated already.
				_cat.debug("seated already from rummysitin : "
						+ origPos);
			} else {

				if (p.getAmtAtTable() < 80 * _tables[tid].POINTSPERRUPEE) {
					buf.append("RummyServer=RummyServer,grid=")
							.append(-1)
							.append(",TID=").append(tid)
							.append(",MsgDropped=PlayerBroke,player-details="
									+ p.name() + "|" + p.netWorth());
					return buf;
				}
				// now check if there is space on the table
//				if (_tables[tid].getCurrentPlayersCount() >= _tables[tid]
//						.getMaxPlayers()) {
//					buf.append("RummyServer=RummyServer,grid=")
//							.append(p.getGRID())
//							.append(",TID=").append(tid)
//							.append(",MsgDropped=TableFull,player-details="
//									+ p.name() + "|" + p.netWorth());
//					return buf;
//				}
				// create a new rummyprofile for this presence
				RummyProfile kp = new RummyProfile();
				kp.setName(p.name());
				kp.setGameStartWorth(p.getAmtAtTable());
				kp.setPresence(p);
				p.setKPIndex(_tables[tid].addObserver(kp));
				kp.setRUMMYStatus(0);
				kp.rummyLastMoveTime = System.currentTimeMillis();
				
				//for private tables, observor and seating is same
				//for other, they are 2 different events
				if (tid < MAX_OPEN_TABLES){
					buf.append("RummyServer=RummyServer,grid=")
							.append(-1)
							.append(",TID=").append(tid);
							
					if (_tables[tid].getCountTotalPlayers() <= 0)
							buf.append(",player-details=" + p.name() + "|"
									+ p.netWorth());
					else {
						String str = "";
						for (int k = 0; k < _tables[tid]._players.length; k++){
							
							if (_tables[tid]._players[k] != null)
								str += _tables[tid]._players[k].getName() + "|" + _tables[tid]._players[k].getPresence().netWorth() + "`";
							
						}
						
						str = str.substring(0, str.length() - 1);
						
						buf.append(",player-details=" + str);
					}
	
					return buf;
				}
				else {
					//change the move here
					moveId = move_RUMMY_JOINED;
				}
			}
		}

		// wants to sit as a player
		// the checks below are for a player who wants to sit on the table
		// he/she should have enough money on table
		// now check for amt on table -- if 0, set it properly
		// if (origPos == -1 && amt != p.getAmtAtTable()) {
		// _cat.debug("do not even respond .....");
		// //return null;
		// }
		if (origPos == -1
				&& p.getAmtAtTable() < 80 * _tables[tid].POINTSPERRUPEE) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",TID=").append(tid)
					.append(",MsgDropped=PlayerBroke,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}
		// now check if there is space on the table
		if (origPos == -1
				&& _tables[tid].getCurrentPlayersCount() >= _tables[tid]
						.getMaxPlayers()) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(p.getGRID())
					.append(",TID=").append(tid)
					.append(",MsgDropped=TableFull,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}

		// need to update the pos in the move det string to be shard with Rummy
		// thread
		StringBuffer movedet1 = new StringBuffer();
		int obsPos = origPos;
		if (obsPos == -1) {
			// player not seated already - check in observors list
			obsPos = _tables[tid].findObsPos(p);
			movedet1.append(tid).append(",").append(obsPos).append(",")
					.append(moveId).append("," + type).append("," + cardsDet);
		} else {
			movedet1.append(tid).append(",").append(obsPos).append(",")
					.append(64).append("," + type).append("," + cardsDet);
		}
		// if obsPos is still -1, then there is a major issue. chuck de phatte
		if (obsPos == -1) {
			_cat.debug("tid : " + tid + " name : " + p.name()
					+ " not found!!!!!!");
			return null;
		}

		_cat.debug("movedet1 : " + movedet1.toString());
		// send this message to the table
		p.setLatestMoveStr(movedet1.toString());
		add(tid, p);

		return null;
	}

	// the moves sent by player will reach this method. it sends them to the
	// correct table
	public StringBuffer rummyMove(Player.Presence p, String movedet) {
		int tid = getTID(movedet);// table id, which table he wants to be seated

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("RummyServer game - move req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",MsgDropped=MsgDropped,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}
		if (tid >= MAX_TABLES) {
			buf.append("RummyServer=RummyServer,grid=")
					.append(-1)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		// send this message to the table
		_cat.debug("ADDING MESSAGE TO RUMMY SERVER  : " + movedet);
		p.setLatestMoveStr(movedet);
		add(tid, p);
		return null;
	}

	public StringBuffer rummyTablesList(Player.Presence p, String mdDet) {
		return gameDetail();
	}

	public void leave(Presence p) {
		int tid = p.getRUMMYTID();
		if (tid == -1)
			return;// no need to do anything, already cleared this player out

		// find if a corresponding rummy profile is present
		RummyProfile kp = _tables[tid].findProfile(p);
		if (kp == null) {
			int obs = _tables[tid].findObsPos(p);
			if (obs > -1) {
				kp = _tables[tid].findObservor(obs);
				if (kp != null) {
					_tables[tid].removeObserver(kp);
				}
			}
			return;
		}

		if (_tables[tid].isLeft(kp)) {
			return;
		}
		
		if (kp._allotedCards == null){
			_cat.debug("never a part of the hand " + kp.pos());
			_tables[tid]._players[kp.pos()] = null;
		}
		else {
			// just mark the player as left. on completion of cycle, the player will
			// be removed
			kp.rummyLeftTableTime = System.currentTimeMillis();
			kp.setRUMMYMovesStr("&Leave");
			kp.setRUMMYMoveId(move_RUMMY_LEFT);
			int pos = kp.pos();
			kp.setRUMMYStatus(status_REMOVED);
			
			_tables[tid].handleMoveNoAction(kp.pos());
			
			if (_tables[tid]._players[pos] != null){
				_cat.debug("take action plyr left : " + kp.pos()
						+ " from table : " + tid);
				kp.setRUMMYStatus(status_LEFT);
			}
		}
	}
}
