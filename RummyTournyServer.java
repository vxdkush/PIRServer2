package com.hongkong.game.casino;

import java.io.IOException;
import java.security.Key;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.Vector;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.atlantis.util.Rng;
import com.atlantis.util.Utils;
import com.hongkong.common.db.DBEAP;
import com.hongkong.common.db.DBException;
import com.hongkong.common.db.DBPlayer;
import com.hongkong.common.db.DBTournyGameLog;
import com.hongkong.common.db.DBTournyWinner;
import com.hongkong.common.db.GameRunSession;
import com.hongkong.common.message.TournyEvent;
import com.hongkong.game.Game;
import com.hongkong.game.GameType;
import com.hongkong.game.Moves;
import com.hongkong.game.Player;
import com.hongkong.game.Player.Presence;
import com.hongkong.game.casino.RummyServer.RummyProfile;
import com.hongkong.game.util.Card;
import com.hongkong.server.GamePlayer;
import com.hongkong.server.GameProcessor;
import com.atlantis.util.Base64;

public class RummyTournyServer extends CasinoGame implements Runnable {
	static Logger _cat = Logger.getLogger(RummyTournyServer.class.getName());
	String _name;
	double _minBet;
	double _maxBet;
	double totalBet = 0, totalWin = 0, totalGames = 0;
	String moveDetails = "";

	// public static final int MAX_PLAYERS = 6;
	public static final int move_RUMMY_INVALID = 0;
	public static final int move_RUMMY_FOLD = 1;
	public static final int move_RUMMY_NEWCARD = 2;
	public static final int move_RUMMY_DISCARD = 4;
	public static final int move_RUMMY_DECLARE = 8;
	public static final int move_RUMMY_RUMMYCUT = 16; //not being used, so using 16 for rejoin
	public static final int move_RUMMY_LEFT = 32;
	public static final int move_RUMMY_JOINED = 64;
	public static final int move_RUMMY_DECLARE_LOSER = 128;

	public static final int move_RUMMY_REGISTER = 256;
	public static final int move_RUMMY_DEREGISTER = 512;
	public static final int move_RUMMY_TOURNYDETS = 1024;
	
	//for bots
	public static final int move_RUMMY_BOTREGISTER = 2048;
	
	public static final int move_RUMMY_CHAT = 4096;

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

	public static int MAX_TOURNY = 50;

	public static final int NONE = -1;
	public static final int CREATED = 0;
	public static final int REG_PENDING = 1;
	public static final int START_PENDING = 2;
	public static final int RUNNING = 3;
	public static final int END_PENDING = 4;
	public static final int DESTROYED = 5;
	public static final int CANCELLED = 6;
	
	ArrayList<RummyTournyTable> _tablesTourny = null;
	
	public static final int MAX_GAMES_PER_ROUND = 3;
	
	//0th index will be used when players <= 20.
	//1st index will be used when players <= 30.
	//2nd index will be used when players <= 50
	//3rd index will be used when players <= 100
	//4th index will be used when players <= 300
	//5th index will be used when players >300
	double[][] winnersPerc = {{75, 25}, {50, 30, 20}, {40, 24, 16, 12, 8}, 
			{30, 20, 12, 9.25, 7.5, 6.25, 5.25, 4.25, 3.25, 2.25},
			{27.5, 17.5, 11.5, 8.5, 7.25, 5.75, 4.5, 3.0, 2.0, 1.5, 1.2, 1.2, 1.2, 1.2, 1.2, 1.0, 1.0, 1.0, 1.0, 1.0},
			{25, 15, 10, 7.5, 6, 5, 4, 2.5, 1.6, 1.4, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.2, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0}
	};

	boolean _keepChkDB = true;
	String listOfTourneys = "";
	
	private ArrayList<LinkedList> _msgQTourny = null;

	public void addMT(int index, Presence cmdStr) {
		(_msgQTourny.get(index)).add(cmdStr);
	}

	public Presence fetchMT(int index) {
		return (Presence) (_msgQTourny.get(index).removeFirst());
	}

	public RummyTournyServer(String name, double minB, double maxB,
			GameType type, int gid) {
		_gid = gid;
		_name = name;
		_minBet = minB;
		_maxBet = maxB;
		_type = type;
		_cat.debug(this);
		
		//get from db all information of rummy tournaments
		String result = DBPlayer.getListOfRummyTournys();
		listOfTourneys = result;
		
		if (!result.isEmpty()){
			String[] resToks = result.split("\\|");
			MAX_TOURNY = resToks.length;
			_msgQTourny = new ArrayList<LinkedList>();
			
			for (int i = 0; i < MAX_TOURNY; i++) {
				_msgQTourny.add(new LinkedList());
			}
			
			_cat.debug("tourneys to start");
			_tablesTourny = new ArrayList<RummyTournyTable>();
			for (int i = 0; i < MAX_TOURNY; i++) {
				String[] resToks2 = resToks[i].split("\\'");
				
				RummyTournyTable rtt = new RummyTournyTable();
				
				rtt.state = CREATED;
				
				//so that we start a tourny after every 1 minutes
				rtt.timeCreated = System.currentTimeMillis() + (60000 * 1 * i);
				
				rtt.createdTournyTime = Calendar.getInstance();
				
				rtt.fees = Double.parseDouble(resToks2[2]);
				
				rtt.toReplicate = Integer.parseInt(resToks2[1]) > 0 ? false : true;
				
				//hack. starting tourny again for now.
				rtt.toReplicate = true;
				
				rtt.name = resToks2[0];
				
				rtt.setIndex(i);
				
				_tablesTourny.add(rtt);
				
				//TBD - resToks2[3] holds the game state. if it is > 0, then it means valid tournys else don't spawn them.
				
				_cat.debug("tourney started :Rummy-Tourny-" + i);
				
				Thread t = new Thread(rtt);
				t.setName("Rummy-Tourny-" + i);
				t.setPriority(Thread.NORM_PRIORITY);
				t.start();

				_cat.debug("Rummy Tournament Server starting thread for tournament : " + i + " with name " + rtt.name);
			}
		}
		
		//now start a thread to keep track of tournaments being added in db
		Thread t = new Thread(this);
		t.setName("Rummy-Scheduler");
		t.setPriority(Thread.NORM_PRIORITY);
		t.start();
	}

	public StringBuffer gameDetail() {
		StringBuffer sb;
		sb = new StringBuffer("RummyTournyServer=").append(_name).append(",min-bet=")
				.append(_minBet).append(",max-bet=").append(_maxBet)
				.append(",RummyTournys=");
		// now add details of all tables
		for (int i = 0; i < MAX_TOURNY; i++) {
//				sb.append(_tablesTourny.get(i).getIndex()).append("'").append(_tablesTourny.get(i).name).append("'").append(_tablesTourny.get(i).getTotalPlayers());
			StringBuffer actStrBuf = _tablesTourny.get(i).getTournyInfo();
			String actStr = actStrBuf.toString();
			//change , to ' so that it becomes one huge mass of characters and not separate tags
			actStr = actStr.replace(",", "'");
			sb.append(actStr);
			if (i != MAX_TOURNY - 1)
				sb.append(":");
		}
		return sb;
	}

	class RummyTournyTable implements Runnable {
		RummyTournyTable() {
			//TBD - check what can we initialize safely here? can't be name or fees or state
			indexWinnersPerc = -1;
			listNosApplied = new ArrayList<Integer>();
		}
		
		private LinkedList[] _msgQ = null;

		public void add(int index, Presence cmdStr) {
			(_msgQ[index]).add(cmdStr);
		}

		public Presence fetch(int index) {
			return (Presence) (_msgQ[index].removeFirst());
		}
		
		private ArrayList<Integer> listNosApplied = new ArrayList<Integer>();

		public int state = -1;
		
		public boolean _keepTournyServicing = true;
		
		public String name = "";
		
		public boolean toReplicate = false;
		
		double rake;
		static final double RAKE_PERCENTAGE = 10;
		
		int indexWinnersPerc; //this will be set so that actual winners get win amt
		
		double prize;
		
		double fees;

		int index = -1;// for tid purposes
		
		Vector registeredPlayers = new Vector();
		ArrayList<RummyProfile> holderRegisteredPlayers = null;
		
		ArrayList<ClassRank> listWinners = null;
		
		Vector tourneyObservors = new Vector();
		
		boolean resultsShared = false;
		
		long timeStarted = -1, timeRegOpen = -1, timeCreated = -1, timeEnded = -1, timeDied = -1;
		Calendar createdTournyTime;
		
		int counterUpdates = 0;
		
		RummyTable[] tables = null;
		
		private double ptsPerRupee = 0.5;
		
		//0th index will be used when players <= 20.
		//1st index will be used when players <= 30.
		//2nd index will be used when players <= 50
		//3rd index will be used when players <= 100
		//4th index will be used when players > 100
		public void setWinnersPerc(int totalPlayersAtGST){
			int val = 0;
			if (totalPlayersAtGST <= 20)
				val = 0;
			else if (totalPlayersAtGST <= 30)
				val = 1;
			else if (totalPlayersAtGST <= 50)
				val = 2;
			else if (totalPlayersAtGST <= 100)
				val = 3;
			else if (totalPlayersAtGST > 100)
				val = 4;
			
			indexWinnersPerc = val;
		}

		public int getWinnersCount(){
			return winnersPerc[indexWinnersPerc].length;
		}
		
		public double getWinnerPerc(int index){
			if (index < winnersPerc[indexWinnersPerc].length)
				return winnersPerc[indexWinnersPerc][index];
			else
				return 0;
		}
		
		public void setIndex(int i) {
			index = i;
		}

		public int getIndex() {
			return index;
		}

		public int addRegPlayer(Presence p) {
			// first check if he is already in this list, if so don't do
			// anything
			int i = 0;
			for (i = 0; i < registeredPlayers.size(); i++) {
				Presence pro = (Presence) registeredPlayers.get(i);
				if (p.name().compareToIgnoreCase(pro.name()) == 0)
					return i;
			}
			
			//check now if the player has enough money for the tourny fees
			_cat.debug("player " + p.name() + " has " + p.getAmtAtTable());
			if (p.getAmtAtTable() >= fees){
				p.resetRoundBet();
				p.currentRoundBet(fees);
				registeredPlayers.add(p);
				p.setRUMMYTournyTID(index);
				_cat.debug("registering player : " + p.name()
						+ " on tourney : " + index);
				
				//now remove this player from observors
				tourneyObservors.remove(p);
			}
			
			
			return registeredPlayers.size();
		}

		public void deRegPlayer(Presence p) {
			p.returnRoundBet(fees);
			registeredPlayers.remove(p);
			p.setRUMMYTournyTID(-1);
			//now add this player from observors
			tourneyObservors.add(p);
		}

		//method to create tables, seat players 
		public boolean createTablesAndLaunch(int numTotalPlayers, double ptsPerRupee) {
			if (indexWinnersPerc < 0){
				setWinnersPerc(numTotalPlayers);
				int count = getWinnersCount();
				listWinners = new ArrayList<ClassRank>(count);
				_cat.debug("WINNERS TO BE BE BE : " + count + " for total players :::::: " + numTotalPlayers);
				
				//now set the prize
				prize = fees * registeredPlayers.size();
				rake = (prize * RAKE_PERCENTAGE) / 100;
				prize -= rake;
				
				_cat.debug("prize is " + prize);
			}
			
			int valHiPlyrs = 0;
			int valLoPlyrs = 0;
			int numTablesHiPlyrs = 0;
			int numTablesLoPlyrs = 0;
			
			int maxTables = 0;
			valHiPlyrs = 6;// this can go down to a low of 4. e.g for 7 players,
							// we need 1 4player table and 1 3 player
			valLoPlyrs = valHiPlyrs - 1;
			
//			boolean flag = true;
			boolean foundFlag = false;
			
			numTablesHiPlyrs = numTotalPlayers / valHiPlyrs;
			numTablesLoPlyrs = 1;
			
			//TBD - here even if 2 players are there, table creation is allowed
			//but in reality this should be a big no. something like 10 or 30.
			if (numTotalPlayers < 2) {
				numTablesHiPlyrs = 0;
				valHiPlyrs = 0;
				valLoPlyrs = -1;
				numTablesLoPlyrs = 0;
				
				//return the money to the sole player registered
				if (registeredPlayers.size() > 0) {
					Presence p = (Presence) registeredPlayers.get(0);
					if (p != null)
						p.returnRoundBet(fees);
				}
				
				state = CANCELLED;
				return false;
			} 
			else if (numTotalPlayers <= 6){
				//if there are <= 6 players, all of them will have to be adjusted to one table only
				numTablesHiPlyrs = 1;
				valHiPlyrs = numTotalPlayers;
				valLoPlyrs = -1;
				numTablesLoPlyrs = 0;
				foundFlag = true;
			}
			else {
					// check a basic case where we can get equal number of
					// players on all tables
					int tempValPlyrs = valHiPlyrs;
					while (true) {
						int num = numTotalPlayers % tempValPlyrs;
						if (num == 0) {
							foundFlag = true;
							break;
						} else {
							tempValPlyrs--;
							if (tempValPlyrs < 5) {
								break;
							}
						}
					}

					if (foundFlag) {
						// we found it
						valHiPlyrs = tempValPlyrs;
						valLoPlyrs = -1;
						numTablesHiPlyrs = numTotalPlayers / valHiPlyrs;
						numTablesLoPlyrs = 0;
					} else {
						//this is the case where tables will have different number of players but
						//we enforce the condition of keeping the difference to 1
							int tempTotPlyrs = valHiPlyrs * numTablesHiPlyrs
									+ valLoPlyrs * numTablesLoPlyrs;
							
							_cat.debug("num total : " + numTotalPlayers + ", temp count : " + tempTotPlyrs);
							if (tempTotPlyrs == numTotalPlayers){
								foundFlag = true;
							}
							else {
								while (tempTotPlyrs > numTotalPlayers) {
									int tempHiValPlyrs = valHiPlyrs;
									int tempLoValPlyrs = valLoPlyrs;
									
									numTablesHiPlyrs--;
									if (numTablesHiPlyrs < 0) {
										// solution didn't work out
										valHiPlyrs = tempHiValPlyrs - 1;
										valLoPlyrs = tempLoValPlyrs - 1;
										// error condition -
										if (valLoPlyrs == 1) {
											break;
										}
										
										numTablesHiPlyrs = numTotalPlayers / valHiPlyrs;
										numTablesLoPlyrs = 1;
										
										_cat.debug("hi plyrs : " + valHiPlyrs + " , numHiTables : " + numTablesHiPlyrs);
										_cat.debug("lo plyrs : " + valLoPlyrs + " , numLoTables : " + numTablesLoPlyrs);
									}
									else {
										numTablesLoPlyrs++;
									}
									
									int newtempTotPlyrs = valHiPlyrs * numTablesHiPlyrs
											+ valLoPlyrs * numTablesLoPlyrs;
									
									if (newtempTotPlyrs == numTotalPlayers
											&& (numTablesHiPlyrs > 0 || numTablesLoPlyrs > 0)
											) {
										foundFlag = true;
										break;
									}
									
									if (newtempTotPlyrs > tempTotPlyrs){
										//make numTablesHiPlyrs 1 so that in next iteration
										//we hit the block where table count is 0
										numTablesHiPlyrs = 0;
										tempTotPlyrs = newtempTotPlyrs;
									}
								}
							}
							
							_cat.debug("after loop hi plyrs : " + valHiPlyrs + " , numHiTables : " + numTablesHiPlyrs);
							_cat.debug("after loop lo plyrs : " + valLoPlyrs + " , numLoTables : " + numTablesLoPlyrs);
							
					}
			}

			// handling of error condition - hopefully we never encounter this
			if (!foundFlag) {
				_cat.debug("error. a suitable combo couldn't be found to launch tables. cancelling tournament ...");
				state = CANCELLED;
				return false;
			}
			
			_cat.debug("num hi tables : " + numTablesHiPlyrs + ", num lo tables : " + numTablesLoPlyrs);
			_cat.debug("val hi plyrs : " + valHiPlyrs + ", val lo plyrs : " + valLoPlyrs);

			maxTables = numTablesHiPlyrs + numTablesLoPlyrs;
			
			_cat.debug("max tables : " + maxTables);
			_msgQ = new LinkedList[maxTables];

			// one linkedlist per thread
			for (int i = 0; i < maxTables; i++) {
				_msgQ[i] = new LinkedList();
			}
			
			tables = new RummyTable[maxTables];
			for (int i = 0; i < maxTables; i++) {
				tables[i] = new RummyTable(index);
				tables[i].validTable = true;

				if (i < numTablesHiPlyrs)
					tables[i].numPlyrsOnTable = valHiPlyrs;
				else
					tables[i].numPlyrsOnTable = valLoPlyrs;

				tables[i].POINTSPERRUPEE = ptsPerRupee;
				
				tables[i].setTournyIndex(index);
				
				tables[i].setTablePrize(prize);
				tables[i]._winnerPos = -1;
				tables[i].fixingDealerNextHand = true;
				
				tables[i]._keepServicing = true;

				Thread t = new Thread(tables[i]);
				t.setName("Rummy-Table-" + i);
				t.setPriority(Thread.NORM_PRIORITY);
				tables[i].setIndex(i);
				t.start();

				_cat.debug("starting thread : " + i);
			}

			// now go about setting the players on various tables
			//have to hold the originally registered players
			boolean toHoldPlayers = false;
			if (holderRegisteredPlayers == null){
				holderRegisteredPlayers = new ArrayList<RummyProfile>(registeredPlayers.size());
				toHoldPlayers = true;
			}
			
			int indexOnTable = 0;
			BitSet deck = new BitSet();
			
			boolean stillSeating = true;
			while (stillSeating) {
				for (int selTable = 0; selTable < maxTables; selTable++) {
					//check if all players are seated.
					_cat.debug("count of players adjusted : " + deck.cardinality());
					if (deck.cardinality() + 1 >= numTotalPlayers){
						//this player to be seated is the last one.
						stillSeating = false;
						_cat.debug("last player to be seated....");
					}
					
					if (deck.cardinality() >= numTotalPlayers) {
						_cat.debug("breaking out of while loop for deck is full");
						break;
					}
					
					int randIndex = Rng.nextIntBetween(0, numTotalPlayers);
					while (deck.get(randIndex)) {
						randIndex = Rng.nextIntBetween(0, numTotalPlayers);
					}
					deck.set(randIndex);
					
					Presence p = (Presence) registeredPlayers.get(randIndex);
					
					RummyProfile kp = new RummyProfile();
					kp.setName(p.name());
					
					//give the player more chips on top of what he has already won
					kp.setGameStartWorth(80 * MAX_GAMES_PER_ROUND + kp.getGameStartWorth());
					_cat.debug("player got " + kp.getGameStartWorth());
					
					kp.setPresence(p);
					kp.setRUMMYStatus(0);
					kp.rummyLastMoveTime = System.currentTimeMillis();
					// now select the table for this player and set the rummy tid
					// for same
					if (indexOnTable < tables[selTable].numPlyrsOnTable) {
						// we can add the player here
						tables[selTable].handleGameJoinReq(indexOnTable, kp);
					} 
					else {
						//plyrsAdded acts as index. so once all 0 pos are seated
						//we go for pos 1.
						indexOnTable++;
					}
					
					if (toHoldPlayers){
						holderRegisteredPlayers.add(kp);
					}
				}
			}
			
			//before starting game, make a broadcast to all players. very imp
			//so that tables list can be shown
			state = RUNNING;
			timeStarted = System.currentTimeMillis();
			_cat.debug("SM-TOURNEY : moving to RUNNING state");
			broadcastTournyUpdates(1);
			
			//clear the registered list now. to be populated later with winners of various tables
			//then we can call createtablesandlaunch once again
			registeredPlayers.clear();

			return true;
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

		public void sendTournyUpdates(Presence pr, String str){
//			_cat.debug("send tourny update response!!! " + index);
			StringBuffer temp = getTournyInfo();
			//add a tag to inform all that this is a regular tourney broadcast
			temp.append(",RummyTourneyUpdates=1");
			temp.append("," + str + pr.name());
			sendMessage(temp, pr);
		}
		
		public void broadcastTournyUpdates(int clearTables){
//			_cat.debug("send tourny update response!!!");
			StringBuffer temp = getTournyInfo();
			
			//add a tag to inform all that this is a regular tourney broadcast
			temp.append(",RummyTourneyUpdates=2");
			
			//add a tag to inform client to clear out tables
			temp.append(",ClearTables=").append(clearTables);
			
			//if game running then players are getting their fill of messages
			//observors will keep on getting their fill
			//loop thru all players and send message to each one of them
			if (holderRegisteredPlayers != null){
				for (int i = 0; i < holderRegisteredPlayers.size(); i++){
					RummyProfile rp = holderRegisteredPlayers.get(i);
					if (rp != null && !rp.pr.isBot){
						sendMessage(temp, rp.getPresence());
					}
				}
			}
			else {
				for (int i = 0; i < registeredPlayers.size(); i++){
					Presence rp = (Presence) registeredPlayers.get(i);
					if (rp != null && !rp.isBot){
						sendMessage(temp, rp);
					}
				}
			}
			
			//tourney observors get the broadcast always
			for (int i = 0; i < tourneyObservors.size(); i++){
				Presence rp = (Presence) tourneyObservors.get(i);
				if (rp != null && !rp.isBot){
					sendMessage(temp, rp);
				}
			}
		}
		
		private StringBuffer getTournyInfo(){
			StringBuffer temp = new StringBuffer();
			temp.append("RummyTournyServer=RummyTournyServer");
			
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(_grid);
			
			temp.append(",tournyid=").append(index);
			temp.append(",tournystate=").append(state);
			temp.append(",name=").append(name);
			temp.append(",fees=").append(fees);
			temp.append(",timeCreated=").append(timeCreated);
			
			long counterTimeSinceReg = (System.currentTimeMillis() - timeRegOpen)/1000;
			long value = 300 - counterTimeSinceReg;
			if (value < 0)
				value = -1;
			temp.append(",timeStarted=").append(value);
			
			temp.append(",openForReg=").append(state == REG_PENDING ? "yes" : "no");
			
			temp.append(",min-bet=").append(_minBet).append(",max-bet=").append(_maxBet);
			
			StringBuffer buf = new StringBuffer("");
			//for names of registered players, check if game started. if so, use holder var
			if (holderRegisteredPlayers != null){
				for (int i = 0; i < holderRegisteredPlayers.size(); i++){
					RummyProfile rp = holderRegisteredPlayers.get(i);
					if (rp != null){
						buf.append(rp.name + "`");
					}
				}
				if (holderRegisteredPlayers.size() > 0)
					buf.deleteCharAt(buf.length() - 1);
			}
			else {
				for (int i = 0; i < registeredPlayers.size(); i++){
					Presence rp = (Presence) registeredPlayers.get(i);
					if (rp != null){
						buf.append(rp.name() + "`");
					}
				}
				if (registeredPlayers.size() > 0)
					buf.deleteCharAt(buf.length() - 1);
			}
			
			if (buf.length() > 1)
				temp.append(",names=" + buf);
			
			if (state == RUNNING){
				temp.append(",prize=").append(prize);
				temp.append(",winnerCount=").append(getWinnersCount());
				String buf2 = ",winnerPerc=";
				for (int i = 0; i < getWinnersCount(); i++){
					buf2 += getWinnerPerc(i) + "!";
				}
				buf2 = buf2.substring(0,  buf2.length() - 1);
				temp.append(buf2);
				
				//now add the details of all tables that are running as part of tourney
				temp.append(",RummyTables=");
				StringBuffer sb = new StringBuffer();
				for (int i = 0; i < tables.length; i++) {
					double dbl = tables[i].POINTSPERRUPEE;
					sb.append(dbl);
					sb.append("'");
					sb.append(tables[i]._tid).append(
							"'" + tables[i].getMaxPlayers());
					sb.append("'" + tables[i].getCurrentPlayersCount());
					String details = tables[i].getCurrentPlayersDetail();
					sb.append("'" + details);

					if (i != tables.length - 1)
						sb.append(":");
				}
				temp.append(sb.toString());
			}
			else if (state == END_PENDING){
				//send tourney results to clients...
				temp.append(",prize=").append(prize);
				temp.append(",winnerCount=").append(getWinnersCount());
				String buf2 = ",winnerPerc=";
				for (int i = 0; i < getWinnersCount(); i++){
					buf2 += getWinnerPerc(i) + "`";
				}
				buf2 = buf2.substring(0,  buf2.length() - 1);
				temp.append(buf2);
				
				//don't send rummy tables. they are dead!!!
				temp.append(",TourneyWinners=");
				String tempStr = "";
				for (int i = 0; i < listWinners.size(); i++) {
					if (listWinners.get(i) == null)
						continue;

					RummyProfile pr = listWinners.get(i).getRummyProfile();
					tempStr += pr.name + "%";
					tempStr += listWinners.get(i).getRank() + "%";
					
					tempStr += listWinners.get(i).getWinAmt() + "^";
				}
				tempStr = tempStr.substring(0, tempStr.length() - 1);
				
				temp.append(tempStr);
				_cat.debug("winner info : " + tempStr);
			}
			
			return temp;
		}

		public void processTournyMsgForTable(String movedet, Presence p) {
			// handle register, deregister here. if message meant for game
			// table, pass it on
			int tid = getTID(movedet); //this is tournament id
			int tableId = getTableIndex(movedet);
			int moveId = getMoveId(movedet);
			String type = getType(movedet);
			//tourny details message will be handled at any point of game. even if state is destroyed
			if (moveId == move_RUMMY_TOURNYDETS){
//				_cat.debug("asking for tourny data : " + index);
				sendTournyUpdates(p, "TOURNEYDET=");
				
				//now add this player to tourneyobservors so that it keeps getting timely updates
				if (state < 3 && !tourneyObservors.contains(p))
					tourneyObservors.add(p);
				return;
			}
			
			//special move for adjusting bots on table
			if (moveId == move_RUMMY_BOTREGISTER && state == REG_PENDING){
				//get list of bots from cardsdet
				String botsNames = getCardsDet(movedet);
				String[] names = botsNames.split("\\'");
				
				_cat.debug("bots to be registered from logged in : " + names[0]);
				
				//putting here a check to disallow request with same bot names
				_cat.debug("value of type is " + type);
				int typeI = Integer.parseInt(type);
				_cat.debug("typeI : " + typeI);
				_cat.debug("size : " + listNosApplied.size());
				if (listNosApplied.size() > 0){
					if (listNosApplied.get(typeI) != null)
						_cat.debug("get(typeI) : " + listNosApplied.get(typeI));
					else
						_cat.debug("get(typeI) is null");
					if (listNosApplied.size() < typeI
							&&listNosApplied.get(typeI) != null 
							&& listNosApplied.get(typeI) > -1){
						//done with this bozo. ignore.
						_cat.debug("why are we here ? " + typeI);
						return;
					}
				}
				_cat.debug("add to arraylist now...");
				listNosApplied.add(typeI);
				_cat.debug("successfully checked the listnosapplied condition");
				
				//now we have the names
				//cn=1, cn=2, cn=6 and cn=7&mv=10 for this table,
				//all to be done in one go
				//0th entry has to be the player who has already registered
				GamePlayer gp = GamePlayer.getPlayer(names[0]);
				for (int m = 1; m < names.length; m++){
					_cat.debug("calling bot creator for name : " + names[m]);
					Presence bp = GameProcessor.handleBot(names[m], _gid, gp._handler);
					
					//first player is a genuine client.
					if (m > 0) {
						bp.isBot = true;
						bp.sid = Rng.getNewSessionId();
					}
					//now register the presence just now created
					addRegPlayer(bp);
				}
				
				//finally add the player also
				addRegPlayer(gp.presence(_gid));
				
				//now finally send a tourney broadcast
				sendTournyUpdates(p, "TOURNEYBOT=");
				return;
			}

			// first handle the case of sit in request
			if (moveId == move_RUMMY_REGISTER && state == REG_PENDING) {
				addRegPlayer(p);
				sendTournyUpdates(p, "TOURNEYREG=");
				return;
			} else if (moveId == move_RUMMY_DEREGISTER && state == REG_PENDING) {
				deRegPlayer(p);
				sendTournyUpdates(p, "TOURNEYDEG=");
				return;
			} else {
				// accept message only if table is active
				// seems a message for a playing gaming table, pass the message
				_cat.debug("moves for rummy table : " + movedet + " and state is : " + state + " for index : " + index);
				if (state != RUNNING)
					return;

				p.setLatestMoveStr(movedet);
				_tablesTourny.get(tid).add(tableId, p);
			}
		}
		
		//for t_user_eap
		public void saveRakeEAP(){

			StringBuffer sb = new StringBuffer();
			_cat.debug("total players in hand : " + holderRegisteredPlayers.size());
			//for t_user_eap, this is the only place
			new Thread(){
				public void run(){
					double indrake[];
					indrake = Utils.integralDivide(rake, holderRegisteredPlayers.size());
					
					for (int m = 0; m < holderRegisteredPlayers.size(); m++){
						
						DBEAP dbeap = new DBEAP(holderRegisteredPlayers.get(m).name, indrake[m]);
						try {
							dbeap.setUserEAP();
						} catch (DBException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}.start();
		}
		
		//for t_tourny_per_player
		public void saveTournamentPlayers() {
			StringBuffer sb = new StringBuffer();
			_cat.debug("total players in hand : " + holderRegisteredPlayers.size());

			// loop thru the players, find the participating players
			// insert their entries in t_player_per_grs
			DBTournyGameLog grs = new DBTournyGameLog(index, -1, -1, GameType.Rummy);
			grs.setEndTime(new Date());
			
			if (createdTournyTime != null)
				grs.setStartTime((createdTournyTime.getTime()));
			else
				grs.setStartTime(new Date(timeCreated));
			// ************STARTING BATCH
			int j = -1;
			try {
				Statement grs_stat = grs.startBatch();
				for (int i = 0; i < holderRegisteredPlayers.size(); i++) {
					if (holderRegisteredPlayers.get(i) == null)
						continue;

					RummyProfile pr = holderRegisteredPlayers.get(i);
					j++;
					
					//taking this opportunity to clear out totalWorth in presence
					pr.getPresence().clearTotalWorth();

					GamePlayer gp = (GamePlayer) pr.getPresence().player();
					grs.setDisplayName(pr.name);
					grs.setPosition(i);
					grs.setPot(-1);
					
					//the penalty is deducted so it is our betamnt
					grs.setStartWorth(80 * MAX_GAMES_PER_ROUND);
					
					grs.setEndWorth(pr.getGameStartWorth());

					grs.setWinAmount(grs.getEndWorth() - grs.getStartWorth());
					
					if (pr.getPresence().isBot)
						grs.setSessionId(pr.getPresence().sid);
					else
						grs.setSessionId(gp.session());
					
					grs.setRake(0);

					grs.save(grs_stat);
				}
				// COMMITTING BATCH
				grs.commitBatch(grs_stat);
				_cat.debug("holder reg players committed to tourny_per_player...");
				// TBD- login session have to be updated with win and bet
			} catch (Exception ex) {
				_cat.debug("Exception - " + ex.getMessage());
			}
		}
		
		//for t_tourny_winner
		public void saveTournamentWinners() {
			StringBuffer sb = new StringBuffer();
			_cat.debug("total players in hand : " + listWinners.size());

			// loop thru the players, find the participating players
			// insert their entries in t_player_per_grs
			DBTournyWinner grs = new DBTournyWinner();
			if (createdTournyTime != null)
				grs.setStartTime((createdTournyTime.getTime()));
			else
				grs.setStartTime(new Date(timeCreated));
			
			grs.setTournyId(index);
			// ************STARTING BATCH
			int j = -1;
			try {
				Statement grs_stat = grs.startBatch();
				for (int i = 0; i < listWinners.size(); i++) {
					if (listWinners.get(i) == null)
						continue;

					RummyProfile pr = listWinners.get(i).getRummyProfile();
					//for bot, no entry to table
					if (pr.getPresence().isBot)
						continue;
					
					j++;

					grs.setDisplayName(pr.name);
					grs.setRank(listWinners.get(i).getRank());
					
					grs.setAmount(listWinners.get(i).getWinAmt());
					
					grs.save(grs_stat);
				}
				
				if (j != -1) {
					// COMMITTING BATCH
					grs.commitBatch(grs_stat);
				}
				
				_cat.debug("winners committed to tourny_winners...");
				// TBD- login session have to be updated with win and bet
			} catch (Exception ex) {
				_cat.debug("Exception - " + ex.getMessage());
			}
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			while (_keepTournyServicing) { // handle message only when active or
											// registration pending
				String moveStr = null;
				try {
					Presence p = fetchMT(index);
					moveStr = p.getLatestTournyMoveStr();
					_cat.debug("tourney movestr from run : " + moveStr + " for index : " + index);
					if (moveStr != null) {
						processTournyMsgForTable(moveStr, p);
					}

				} catch (NoSuchElementException e) {
				} catch (Exception ex) {
				}

				long latestTime = System.currentTimeMillis();
				
				//state machine of the tournament to be coded here. for each tournament.
				if (state == CREATED){
					//no message to be handled here. only thing to check is how and when to open registration
					if (timeCreated != -1 && (latestTime - timeCreated > 20*1000)){
						_cat.debug("SM-TOURNEY : moving to reg pending state :" + index);
						//waiting for 10 seconds only. open tournament for registration.
						state = REG_PENDING;
						timeRegOpen = System.currentTimeMillis();
					}
					
					//send update messages to all registered players
					counterUpdates++;
					//perhaps 60 should be 10.
					if (counterUpdates >= 60) {
						broadcastTournyUpdates(0);
//						_cat.debug("broadcasting tourney updates : " + index);
						counterUpdates = 0;
					}
				}
				else if (state == REG_PENDING){
					//we only handle registration related message. player can register or deregister
					//we do however check the time. changing time to 2 minutes. let us revert back to 5 minutes
					if (timeRegOpen != -1 && (latestTime - timeRegOpen > 300*1000)){
						//waiting for 10 seconds only. open tournament for registration.
						_cat.debug("SM-TOURNEY :moving to START_PENDING state " + index);
						state = START_PENDING;
					}
					else {
						//send update messages to all registered players
						counterUpdates++;
						//perhaps 60 should be 10.
						if (counterUpdates >= 60) {
							broadcastTournyUpdates(0);
//							_cat.debug("broadcasting tourney updates : " + index);
							counterUpdates = 0;
						}
					}
				}
				else if (state == START_PENDING){
					//giving time to players to settle down. 5 seconds.
					//waiting for 10 seconds only. open tournament for registration.
					boolean flag = createTablesAndLaunch(registeredPlayers.size(), ptsPerRupee);
				}
				else if (state == RUNNING){
					boolean flag = true;
					for (int i = 0; i < tables.length; i++){
						if (tables[i] != null){
						//	if (counterUpdates >= 20 && counterUpdates < 22)
							//	_cat.debug("table id : " + i + ", game on: " + tables[i]._gameOngoing + ", fixing on : " + tables[i].fixingDealerOngoing + " : " + index);
							if (counterUpdates >= 20 && counterUpdates < 22)
								_cat.debug("cnt games : " + tables[i].getCountGamesPerRound() + ", breakout: " + tables[i].getBreakOutMatch() + ", cnt active :"
									+ tables[i].getCntStatusActivePlayers() + ", winner decl : " + tables[i].getWinnerDeclared() + " : " + index);
							
							if (tables[i].getWinnerDeclared() == 1) {
								//game has to be over before we can add winners
								_cat.debug("***********************************************game over on table : " + tables[i]._tid + " and winner is : " + tables[i].getRoundWinnerPos() + 
										" and last standing player on table is " + tables[i].getOnlyActivePos() + " : " + index);
								if (tables[i].getOnlyActivePos() != -1)
									_cat.debug(" and he has : " + tables[i]._players[tables[i].getOnlyActivePos()].getPresence().getTotalWorth() + " : " + index);
								
								if (tables[i].getRoundWinnerPos() != -1){
									Presence pr = tables[i]._players[tables[i].getRoundWinnerPos()].getPresence();
//									pr.setTotalWorth(tables[i]._players[tables[i].getRoundWinnerPos()].getGameStartWorth());
									registeredPlayers.add(pr);
								}
								else if (tables[i].getOnlyActivePos() != -1){
									Presence pr = tables[i]._players[tables[i].getOnlyActivePos()].getPresence();
//									pr.setTotalWorth(tables[i]._players[tables[i].getOnlyActivePos()].getGameStartWorth());
									registeredPlayers.add(pr);
								}
								else {
									//some major problem. we don't have a winner
									_cat.debug("no winners!!!!!!!!!!!!!!!! " + " : " + index);
								}
								
								tables[i].setWinnerDeclared(2);
								tables[i]._keepServicing = false;
								_cat.debug("killing thread for table : " + i + " of tourney : " + index);
							}
							else if (tables[i].getWinnerDeclared() == 2){
								_cat.debug("reg plyrs : " + registeredPlayers + " for tourney : " + index);
							}
							else {
								//game still ongoing on some table. wait.
								flag = false;
								break;//no need to iterate again.
							}
						}
					}
					
					if (flag){
						//game over on all tables. now start 2nd round.
						
						//list of winners is in registered players. if only 1 winner is there, he is the tourny winner
						if (registeredPlayers.size() == 1){
							//tournament is over.
							_cat.debug("tournament over. " + " : " + index);
							
							//get the list of winners
							Collections.sort(holderRegisteredPlayers);
							
							TreeMap<Double, List<RummyProfile>> ranks = new TreeMap<Double, List<RummyProfile>>(Collections.reverseOrder());
							//find the lowest prev chips and put it last. the highest chips will go first
							//have to find if one or more players have same prev chips.
							for (int m = 0; m < holderRegisteredPlayers.size(); m++){
								RummyProfile pp = (RummyProfile)holderRegisteredPlayers.get(m);
								
								List<RummyProfile> listWithSameRank = ranks.get(pp.getPresence().getTotalWorth());
								if (listWithSameRank == null){
									listWithSameRank = new ArrayList<RummyProfile>();
									ranks.put(pp.getPresence().getTotalWorth(), listWithSameRank);
								}
								listWithSameRank.add(pp);
								_cat.debug("player " + pp.name + " has chips : " + pp.getPresence().getTotalWorth() + " : " + index);
							}
							
							_cat.debug("prize : " + prize + " and rake is : " + rake + " and winner count is " + getWinnersCount() + " : " + index);
							
							//once we have gone thru the list, we have a sorted tree map in ascending order of chips
							//for win amt crediting, the rule is that if 2 players share same pts, then they share win amt
							//like if player at pos 5 and at pos 6 have same pts, then we average out the win amt for pos 5 and 6 and 
							//give it to each of them. No, we are not going to make them play again. Yes, that's final.
							
							int playerWinners = 0;//will keep a count of how many players have been handled
							int rank = 0;//we start from the last person
							for(Map.Entry<Double,List<RummyProfile>> entry : ranks.entrySet()) {
								//don't have to go thru all entries. only those that are needed.
								//suppose there are 27 players and total winner count is 3
								//then we go from last, count till 3 and be done with this list
								//remrmber that the last one gets 0th index of winner perc for it is the winner
								//the prev one will be the 2nd winner
								//the last one will be the 3rd winner
								//check here if all winners have been handled
								if (rank >= getWinnersCount()){
									break;
								}
								_cat.debug("GOING TO HANDLE " + rank + ", till now WINNERS : " + playerWinners + " : " + index);
								
								double amt = getWinnerPerc(rank) * prize / 100;
								
								List<RummyProfile> list = entry.getValue();
								
								if (list.size() == 1){
									ClassRank crn = new ClassRank(list.get(0));
									crn.setRank(rank);
									crn.setChips(list.get(0).getPresence().getTotalWorth());
									crn.setWinAmt(amt);
									listWinners.add(crn);
									_cat.debug("rank " + rank + " of player " + list.get(0).name + " won amt : " + amt + " : " + index);
									playerWinners++;
									rank++;
								}
								else {
									//for all these players with same prev chips, we give different ranks
									//and equal win amt
									amt /= list.size();
									for (int k = 0; k < list.size(); k++){
										ClassRank crn = new ClassRank(list.get(k));
										crn.setRank(rank);
										crn.setChips(list.get(k).getPresence().getTotalWorth());
										crn.setWinAmt(amt);
										listWinners.add(crn);
										_cat.debug("--rank " + rank + " of player " + list.get(k).name + " won amt : " + amt + " : " + index);
										playerWinners++;
									}
									rank += 1;
								}
							}
							
							state = END_PENDING;
							if (timeEnded == -1)
								timeEnded = System.currentTimeMillis();
						}
						else {
							if (registeredPlayers.size() > 1) {
								//TBD. start the next round of tournament with one winner from each table.
								state = START_PENDING;
								_cat.debug("going back to START_PENDING state " + " : " + index);
							}
							else {
								_cat.debug("ERRoRRrrr!!! no player in registered list " + " : " + index);
								state = CANCELLED;
								if (timeEnded == -1)
									timeEnded = System.currentTimeMillis();
							}
						}
					}
					
					//tourney broadcast has to continue
					counterUpdates++;
					//perhaps 60 should be 10.
					if (counterUpdates >= 60) {
						broadcastTournyUpdates(0);
//						_cat.debug("broadcasting tourney updates " + " : " + index);
						counterUpdates = 0;
					}
					
				}
				else if (state == END_PENDING){
					//for db methods. entries for history, win amt crediting
					//call the broadcasttourneyupdates from here. it will carry all results
					//ensure that the method is called only once...
					if (!resultsShared){
						resultsShared = true;
						
						_cat.debug("SM-TOURNEY : end pending state. do the db calls here " + " : " + index);
						
						//now credit the win amt
						for (int i = 0; i < listWinners.size(); i++){
							_cat.debug("player name " + listWinners.get(i).getRummyProfile().name);
							_cat.debug("player disconnection status" + listWinners.get(i).getRummyProfile().isDisconnected());
							if (!listWinners.get(i).getRummyProfile().isDisconnected() && 
									listWinners.get(i).getRummyProfile().getPresence() != null){
								listWinners.get(i).getRummyProfile().getPresence().addToWin(listWinners.get(i).getWinAmt());
								listWinners.get(i).getRummyProfile().setGameStartWorth(listWinners.get(i).getRummyProfile().getPresence().getAmtAtTable());
							}
							else {
								_cat.debug("player not here. keep money with gameprocessor and give on next login... + " + listWinners.get(i).rp.name);
								GameProcessor.saveRummyWinAmt(listWinners.get(i).rp.name, listWinners.get(i).getWinAmt());
							}
						}
						
						broadcastTournyUpdates(0);
						_cat.debug("sent tourney winner info " + " : " + index);
						
						//the db statements. tourney ended. winner information. 
						//15th Sep 2016 -- we need to store the players registered for a tournament
						//and we need to store the winner(s) of a tournament
						//the hands that are played are in any case stored in t_player_per_grs
						//only thing not done is that t_tourny_live is not being updated with tournys being created.
						saveTournamentPlayers();
						
						saveTournamentWinners();
						
						saveRakeEAP();
					}
					
					if (timeEnded != -1 && latestTime - timeEnded >= 60000){
						//once it is all done, move to destroyed state
						state = DESTROYED;
					}
					
					//tourney broadcast has to continue
					counterUpdates++;
					//perhaps 60 should be 10.
					if (counterUpdates >= 60) {
						broadcastTournyUpdates(0);
//						_cat.debug("broadcasting tourney updates " + " : " + index);
						counterUpdates = 0;
					}
				}
				else if (state == DESTROYED || state == CANCELLED){
					//giving some time to jvm to settle down.
					timeDied = System.currentTimeMillis();
					
					broadcastTournyUpdates(0);
					
					_cat.debug("SM-TOURNEY : destroyed state " + " : " + index);
					
					//check if tourny has to be restarted -- check limit
					if (toReplicate){
						RummyTournyTable rtt = new RummyTournyTable();
						rtt.state = CREATED;
						
						//so that we restart tourney after 15 minutes. TBD.
						rtt.timeCreated = timeDied + 30000;
						
						rtt.fees = fees;
						
						rtt.toReplicate = true;
						
						rtt.name = name;
						
						rtt.setIndex(getIndex());
						
//						_tablesTourny.remove(index);
						
						_tablesTourny.set(index, rtt);
						
						_cat.debug("recreating the tourney again..." + rtt.getIndex());
						
						rtt._keepTournyServicing = true;
						
						//copy the observors
						if (holderRegisteredPlayers != null) {
							for (int m = 0; m < holderRegisteredPlayers.size(); m++){
								rtt.tourneyObservors.add(holderRegisteredPlayers.get(m).getPresence());
							}
						}
						
						Thread t = new Thread(rtt);
						t.setName("Rummy-Tourny-" + rtt.getIndex());
						t.setPriority(Thread.NORM_PRIORITY);
						t.start();
					}
					
					state = NONE;
				}
				
				else if (state == NONE){
					this._keepTournyServicing = false;
				}
				
				// now sleep for some time - in the long run i see it as 10 ms or
				// something
				try {
					Thread.currentThread().sleep(700);
				} catch (InterruptedException ee) {
					// continue
				}
			}
		}
	}
	
	class ClassRank {
		RummyProfile rp;
		double chips;
		int rank;
		double winAmt;
		
		ClassRank(RummyProfile rop){
			rp = rop;
		}
		
		public RummyProfile getRummyProfile(){
			return rp;
		}
		
		public void setChips(double val){
			chips = val;
		}
		
		public void setRank(int val){
			rank = val;
		}
		
		public int getRank(){
			return rank;
		}
		
		public void setWinAmt(double val){
			winAmt = val;
		}
		
		public double getWinAmt(){
			return winAmt;
		}
	}

	class RummyProfile implements Comparable {

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
			if (winamt > 0)
				rummyWinAmt += winamt;
			else
				rummyWinAmt = 0;
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
		
		boolean disconnected = false;
		public void setDisconnected(){
			disconnected = true;
			_cat.debug("player " + name + " is disconnected...");
		}
		public void resetDisconnected(){
			disconnected = false;
		}
		
		public boolean isDisconnected(){
			return disconnected;
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
		
		@Override
		public int compareTo(Object o) {
			// TODO Auto-generated method stub
			RummyProfile rp = (RummyProfile) o;
			
			if (this.startWorth < rp.startWorth)
				return -1;
			else if (this.startWorth == rp.startWorth)
				return 0;
			else
				return 1;
		}
	}

	class RummyTable implements Runnable {
		int _tournyId;
		volatile boolean _keepServicing = false;
		volatile boolean _gameOngoing = false;
		double POINTSPERRUPEE = 0.5;

		public int MAX_PLAYERS = 6;
		public int numPlyrsOnTable = 6;//this comes from tourny server. do we want to use it?

		RummyProfile[] _players = new RummyProfile[MAX_PLAYERS];
		// all the players who paid ante or who opted out of ante only - for
		// entries in grs table
		int _nextMovesAllowed;// to remember the moves that are allowed
		int _nextMovePlayer = -1;
		int _dealer;// to mark the very first player
		int _rummyPlayer;// first one to make a move.
		int _winnerPos = -1;//for the particular game
		
		String chatMessage = "";
		int chatOn = 0;
		
		int _roundWinnerPos = -1;//for the entire round
		public int getRoundWinnerPos(){
			return _roundWinnerPos;
		}
		
		RummyTable(int tournyId){
			_tournyId = tournyId;
			
			for (int i = 0; i < MAX_PLAYERS; i++) {
				_players[i] = null;
			}
			_nextMoveExpTime = System.currentTimeMillis();
			clearWinnerDeclared();
		}

		boolean fixingDealerOngoing = false;
		boolean fixingDealerNextHand = false;
		int countHandsAfterDealerFix = 0;

		String _winnerString = "";

		String newCardAdded = "";

		String _cardDealingOrder = "";

		// the card for which the players wage war
		Card _rummyCardJoker, _discardCard, _prevDiscardCard;

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
		int indexDiscardDeck;

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
		boolean noMovesToBeAccepted = false;

		int _tid = -1;

		long lastRespTime = 0;
		
		int tournyIndex = -1;
		public void setTournyIndex(int val){
			tournyIndex = val;
		}
		public int getTournyIndex(){
			return tournyIndex;
		}
		
		int winnerDeclared;
		public int getWinnerDeclared(){
			return winnerDeclared;
		}
		
		public void setWinnerDeclared(int val){
			winnerDeclared = val;
		}
		
		public void clearWinnerDeclared(){
			winnerDeclared = 0;
		}

		//for tournament. track the number of games per round. when count reaches 3, the round gets over
		int countGamesPerRound = 0;
		public void incrCountGamesPerRound(){
			countGamesPerRound++;
		}
		public int getCountGamesPerRound(){
			return countGamesPerRound;
		}
		
		//if 2 players are tied, then a breakout match has to be played.
		int breakOutMatch = -1;
		public int getBreakOutMatch(){
			return breakOutMatch;
		}
		
		double tablePrize = 0;
		public void setTablePrize(double val){
			tablePrize = val;
		}
		
		public RummyTable() {
			for (int i = 0; i < MAX_PLAYERS; i++) {
				_players[i] = null;
			}
			_nextMoveExpTime = System.currentTimeMillis();
			clearWinnerDeclared();
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
			if (_observers == null)
				return -1;
			
			for (int i = 0; i < _observers.size(); i++) {
				RummyProfile pro = (RummyProfile) _observers.get(i);
				if (pro == null)
					return -1;
				
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
			_players[index].setRUMMYStatus(status_REMOVED);
			_players[index].setRUMMYMoveId(move_RUMMY_LEFT);
			_players[index].clearRUMMYMovesStr();
			_players[index].setReloadReq(false);
			_players[index]._allotedCards = null;
			_players[index]._firstMoveDone = false;
		}

		public void resetTable() {
			// first cull the players - this will leave some open seats
			// _cat.debug("from reset table");
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null) {
					if (isRemoved(_players[m])
//							|| isLeft(_players[m])
							|| isBroke(_players[m])
							|| (_players[m].getGameStartWorth() <= 80)//need to have at least 80
					// ||!_players[m].getRUMMYBetApplied() //this condition is
					// to throw out the players who just sit on table
					) {
						//2nd Feb 17 - not doign anything here...
//						sendMessageKickedOut(m, 0);
//						removeFromTable(m);
//						_players[m] = null;
						
						_players[m]._allotedCards = null;
						if (_players[m].getGameStartWorth() <= 80)
							_players[m].setRUMMYStatus(status_BROKE);
						else
							_players[m].setRUMMYStatus(status_REMOVED);
					} else {
						_players[m].clearRUMMYMovesStr();
						_players[m].setReloadReq(false);
						_players[m]._allotedCards = null;
						_players[m]._firstMoveDone = false;
						_players[m].setRUMMYStatus(status_ACTIVE);
						_players[m].rummyLastMoveTime = System
								.currentTimeMillis();
						_players[m].cardsStrFromClient = "";
					}
				}
			}

			// now we contract the table keeping all seated players close
			for (int m = 0; m < MAX_PLAYERS - 1; m++) {
				if (_players[m] == null && _players[m + 1] != null) {
					// shift the next player one step back
					// this is tricky - next player could be one step ahead, two
					// steps ahead, three steps ahead.
					// at max he could be seated 5 steps away
					int k = m + 1;
					if (k >= MAX_PLAYERS)
						break;// very last pos is empty, let it be

					boolean found = false;

					for (; k < MAX_PLAYERS; k++) {
						if (_players[k] != null) {
							found = true;
							break;
						}
					}
					if (found) {
						_players[m] = _players[m + 1];
						_players[m + 1] = null;
						_players[m]._pos = m;
					}
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

			idPlayerChecked = -1;

			idPlayerValidDeclared = -1;

			countPlayerResponseDeclareLoser = 0;

			_nextMoveExpTime = System.currentTimeMillis();

			_deck.clear();
			_discardDeck = new Card[65];
			indexDiscardDeck = 0;

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

			_discardDeck[indexDiscardDeck++] = _discardCard;

			_prevDiscardCard = new Card(randCard);

			_cardDealingOrder = "";
			newCardAdded = "";

			_lastMovePos = -1;
			_lastMove = 0;
			_lastMoveString = "";
			
			clearWinnerDeclared();
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

		public void handleGameJoinReq(int obspos, RummyProfile p) {
			// get the player
			if (p == null) {
				System.out
						.println("can't find the player in observors list or old players : "
								+ obspos);
				return;
			}

			// if (_gameOngoing) {
			// //game in progress, no one can join now, wait for game over
			// _cat.debug("no one joins during game!!!");
			// return;
			// }

			_cat.debug("player found : " + p.getName());
			
			_nextMoveExpTime = System.currentTimeMillis();
			
			int pos = -1;

			int _countPlayersInit = getCountActivePlayers();
			_cat.debug("found " + _countPlayersInit
					+ " players on table " + _tid);
			if (_countPlayersInit >= 2) {

				pos = adjustNextToDealer(p);
				if (pos == -1) {
					System.out
							.println("despite attempts no avail pos at table :"
									+ _tid + " for player : " + p.getName());
					return;
				}

				_cat.debug("new nextmovepos : " + _nextMovePlayer
						+ " , new rummy player tag : " + _rummyPlayer);
				p.setRUMMYStatus(status_ACTIVE);
			} else {
				// less than 2 players - call resettable once
				resetTable();
				_countPlayersInit = getCountTotalPlayers();

				if (_countPlayersInit == 0) {
					pos = 0;// very first player
					_players[0] = p;
				} else if (_countPlayersInit == 1) {
					//check if the already seated plaeyr is on pos 1.
					//if so, give pos 0 to the incoming player
					//after all, there is only 1 player on table. 0 is empty
					if (_players[1] != null)
						pos = 0;
					else
						pos = 1;
					_players[pos] = p;
				}
				p.setRUMMYStatus(status_ACTIVE);// the first 2 players have
												// to be marked active
			}

			// now make him seat on the table on that position
			p.setPresence(p.getPresence());
			p.setPos(pos);
			p.setRummyPlayer();
			p.getPresence().setRUMMYTID(_tid);
			p.getPresence().lastMove(Moves.RUMMY_SITIN);
			p.setRUMMYMoveId(0);
			
			p.setTimeBank(30000);

			addPresence(p);
			System.out
					.println("RummyTournyServer game - sit in req buf -- on table : "
							+ _tid);
			// send message to all players about the new player
			_lastMove = move_RUMMY_JOINED;
			_lastMovePos = pos;
			_lastMoveString = "Joined";

			p.rummyLeftTableTime = -1;
			p.rummyLastMoveTime = System.currentTimeMillis();
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
				if (_players[i] != null )
//						&& !isLeft(_players[i])
//						&&
						// !isFolded(_players[i]) &&
//						!isRemoved(_players[i]) && !isSittingOut(_players[i])
//						&& !isJoined(_players[i]) && !isBroke(_players[i]))
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
				if (_players[i] != null 
						)
//					&& !isLeft(_players[i])
//						&& !isRemoved(_players[i]) && !isBroke(_players[i]))
					cnt++;
			}

			return cnt;
		}

		public int getRightOfPlayer(int pos) {
			int pos1 = decrPos(pos);
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
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);
				
				//just ensure that this player loses all his cards
				_players[pos1]._allotedCards = null;

				pos1 = decrPos(pos1);
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
				if (pos == 0)
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
					|| (_players[pos1].getRUMMYStatus() & status_LEFT) > 0) {
				_cat.debug("find next one : " + pos1 + " , stat: "
						+ _players[pos1].getRUMMYStatus() + " on table : "
						+ _tid);
				
				//if the next player has status left for removed, then put penalty to 80 here itself
				if (((_players[pos1].getRUMMYStatus() & status_REMOVED) > 0) || 
					((_players[pos1].getRUMMYStatus() & status_LEFT) > 0)
					){
					_players[pos1].setRUMMYPoints(80);
					_players[pos1].setRUMMYStatus(status_REMOVED + status_FOLDED);
				}

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
			if (!_gameOngoing || _nextMovePlayer == -1)
				return;

			_lastMovePos = pos;
			_lastMoveString = "Left";

			if (_nextMovePlayer != 111) {

				if (pos == _nextMovePlayer) {
					// same action as that of player leaving table
					handleTimedOut();
					return;
				}

				// not the player making move, but still mark it as folded, set
				// penalty
				if (isLeft(_players[_nextMovePlayer]))
					_players[_nextMovePlayer].setRUMMYPoints(80);
				else if (_players[pos]._firstMoveDone)
					_players[pos].setRUMMYPoints(40);
				else {
					_players[pos].setRUMMYPoints(20);
					// player didn't even play one card, so put his cards in
					// fresh
					// pile
					addCardsToFreshPile(_players[pos]._allotedCards, 13);
				}

//				_players[pos]._allotedCards = null;

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
			else {
				_cat.debug("pleayer " + pos + " leaving at declare stage. make him pay max.");
				_players[pos].setRUMMYPoints(80);
			}
		}

		private void handleTimedOut() {
			//for tournament. if a removed player has not made moves, penalize him full
			_cat.debug("handletimed out and next move player is " + _nextMovePlayer);
			if (_nextMovePlayer == -1)
				return;
			
			if (isLeft(_players[_nextMovePlayer])) {
				_players[_nextMovePlayer].setRUMMYPoints(80);
				_lastMoveString = "FoldedDueToLeft";
			}
			
			else{
				_lastMoveString = "FoldedDueToInaction";
				if (_players[_nextMovePlayer]._firstMoveDone)
					_players[_nextMovePlayer].setRUMMYPoints(40);
				else {
					_players[_nextMovePlayer].setRUMMYPoints(20);
					// player didn't even play one card, so put his cards in fresh
					// pile
					addCardsToFreshPile(_players[_nextMovePlayer]._allotedCards, 13);
				}
			}

			_lastMovePos = _nextMovePlayer;
			_players[_nextMovePlayer].setRUMMYStatus(status_FOLDED);
			
			_players[_nextMovePlayer].setRUMMYMovesStr("&TimedOut");
			
			_players[_nextMovePlayer]._allotedCards = null;
			
			_cat.debug("player timed out " + _nextMovePlayer);

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
			_cat.debug("declaregame over chagned flag : " + _gameOngoing);
			_winnerPos = posWinner;
			int countWinners = 1;
			String resString = "";
			StringBuffer sb = new StringBuffer();
			int countPotContenders = 0;
			
			breakOutMatch = 0;
			
			int totalWinPts = 0;
			for (int m = 0; m < MAX_PLAYERS; m++) {
				if (_players[m] != null
						&& (isActive(_players[m]) || isRemoved(_players[m]) || isFolded(_players[m]))) {
					// compute the points of players still playing
					int pts = 0;
					if (m != _winnerPos) {
						if (isFolded(_players[m]) || isLeft(_players[m])
								|| isRemoved(_players[m]))
							pts = _players[m].getRUMMYPoints();
						else {
							idPlayerChecked = m;
							int[] retVal = checkValidCards2(
									_players[m]._allotedCards,
									_players[m].cardsStrFromClient);
							pts = retVal[0];
						}

						_players[m].setRUMMYPoints(pts);
						totalWinPts += pts;
						_players[m].setRUMMYMovesStr("&Penalty^" + pts);
					}
					//add winner declaration
					_players[m].setRUMMYMovesStr("&Winner^" + _players[_winnerPos].getName());
					sb.append(m + "`" + _players[m].name + "`" + pts + "'");
					countPotContenders++;
				}
			}

			sb.deleteCharAt(sb.length() - 1);

			_players[_winnerPos].setRUMMYPoints(totalWinPts);
			_players[_winnerPos].setRUMMYWinAmt(totalWinPts);
			_players[_winnerPos].setRUMMYMovesStr("&WinPoints^" + totalWinPts);

			// hack - comment later
			_cat.debug("winners win amt computd : " + totalWinPts + " on tid : " + _tid);
			_cat.debug("total players in hand : " + countPotContenders + " on table " + _tid);
			
			//for tournament.
			incrCountGamesPerRound();

//			if (getCountGamesPerRound() >= MAX_GAMES_PER_ROUND){
//				//find out the winner of this round._roundWinnerPos has to be populated here.
//				int pointsWinner = 0, indexWinner = -1;
//				int pointsSame = 0, indexSamePts = -1;
//				for (RummyProfile rp : _players){
//					if (rp != null){
//						if (rp.getRUMMYPoints() > pointsWinner){
//							pointsWinner = rp.getRUMMYPoints();
//							indexWinner = rp.pos();
//						}
//						else if (rp.getRUMMYPoints() == pointsWinner){
//							pointsSame = pointsWinner;
//							indexSamePts = indexWinner;
//						}
//					}
//				}
//				
//				_roundWinnerPos = indexWinner;
//				//now check if roundWinnerPos is tied with some other player
//				if (indexSamePts != -1 && indexSamePts != indexWinner && pointsSame == pointsWinner){
//					//we have a tie.
//					breakOutMatch = 1;//this will make sure that one more round is played.
//					_roundWinnerPos = -1;
//				}
//				else {
//					breakOutMatch = 0;
//				}
//				
//				_cat.debug("table : " + _tid + " has winner " + _roundWinnerPos + ", breakout : " + breakOutMatch);
//			}

			// loop thru the players, find the participating players
			// insert their entries in t_player_per_grs
			GameRunSession grs = new GameRunSession(_gid, rummygrid,
					GameType.Rummy);
			grs.setEndTime(new Date());
			grs.setStartTime(_gameStartTime.getTime());
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
					grs.setDisplayName(pr.getName());//for bots we have to be careful
					grs.setPosition(pr.pos());
					grs.setPot(totalWinPts);
					
					//the penalty is deducted so it is our betamnt
					double betamnt = pr.getRUMMYPoints();
					grs.setStartWorth(pr.getGameStartWorth());

					grs.setWinAmount(0);
					// for winner only these 2 lines
					if (i == _winnerPos) {
						// win amt will be actual win amt after accounting for
						// bet amt
						grs.setWinAmount(totalWinPts);
						grs.setEndWorth(pr.getGameStartWorth() + totalWinPts);
					} else {
						grs.setEndWorth(pr.getGameStartWorth() - betamnt);
					}
					grs.setBetAmt(betamnt);
					// now for all
					
					//for bot, create unique session id here
					if (pr.getPresence().isBot) {
						grs.setSessionId(pr.getPresence().sid);
					}
					else 
						grs.setSessionId(gp.session());
					
					grs.setRake(0);

					grs.setMoveDet(pr.getRUMMYMovesStr());
					grs.save(grs_stat);

					// now set the player's start worth
					_players[i].setGameStartWorth(grs.getEndWorth());
					_cat.debug("player : " + i + " has new start worth : " + _players[i].getGameStartWorth() + " and winner is " + _winnerPos);
				}
				// COMMITTING BATCH
				grs.commitBatch(grs_stat);
				_cat.debug("grs committed...");
				// TBD- login session have to be updated with win and bet
			} catch (Exception ex) {
				_cat.debug("Exception - " + ex.getMessage());
			}

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
			_cat.debug("checkgame over : " + _gameOngoing);
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
						
						_winnerString = declareGameOver(pos);
						_nextMovePlayer = -1;
						broadcastMessage(pos);
					} else {
						// for some reaons either game is not on or pos is -1
						_gameOngoing = false;
					}
				} else {
					// less than 1 player on table - sad state of affairs
					_gameOngoing = false;
				}

				_cat.debug("returning from cgo : " + _gameOngoing);
				return true;
			} else {
				_cat.debug("returning from cgo : " + _gameOngoing);
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
						// _cat.debug("cards Pending : " + toks2[k]);
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
				if (tanalaCount + pureRuns + impureRuns >= 2) {//removing condition runGT4
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
				resTemp[0] = 80;
				resTemp[1] = 0;
				resTemp[2] = 0;
				return resTemp;
			}

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
				//if game is over on this table, don't do anything
				if (getWinnerDeclared() == 2){
					return;//kill this thread from here
				}
				
				String moveStr = null;
				try {
					Presence p = _tablesTourny.get(_tournyId).fetch(_tid);
					moveStr = p.getLatestMoveStr();
					_cat.debug("rummytable movestr from run : " + moveStr + " for tournyid : " + _tournyId + " for table : " + _tid);
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
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 62000) { // should
																						// be
																						// 65
																						// seconds
					_cat.debug("timed out. run finishing game...");
					_winnerString = declareGameOver(idPlayerValidDeclared);
					_nextMovePlayer = -1;
					broadcastMessage(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
					_nextMoveExpTime = System.currentTimeMillis();
				}

				// now check if someone has timed out his moves
				if (_gameOngoing
						&& idPlayerValidDeclared == -1
						&& (System.currentTimeMillis() - _nextMoveExpTime) > 60000) { 
					if (_nextMovePlayer != -1 && _nextMovePlayer != 111)
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
							handleTimedOut();
					}
				}

				// maintenance job - shall we create a separate thread for it?
				// maintenance job - if there is only one player on a table
//				for (int m = 0; m < MAX_PLAYERS && !_gameOngoing; m++) {
//					if (_players[m] != null
//							&& System.currentTimeMillis()
//									- _players[m].rummyLeftTableTime > 3000
//							&& (isLeft(_players[m]))) {
//						// removed condition for waiting player to make a move -
//						// that was creating issues with players who are waiting
//						// to join next game
//						// System.out
//						// .println("nothing from this player, kick him out : "
//						// + _players[m].getName());
//						// before sending the message we should check if client
//						// is showing the result window
//						// if so, this message would curtail the beautiful,
//						// rapturous experience of a winning player
//						sendMessageKickedOut(m, 2);
//
//						// remove the player
//						removeFromTable(m);
////						_players[m] = null;
//					}
//				}

				if (!_gameOngoing
						&& System.currentTimeMillis() - _nextMoveExpTime > 3000 &&
						getWinnerDeclared() == 0) {
					int countPlayers = getCountActivePlayers();//getCountTotalPlayers();
					_cat.debug("tid : " + _tid + " has " + countPlayers + " active players.");
					if (countPlayers >= 2) {
						if (getCountGamesPerRound() <= 0)
							fixDealerFirst();
						else {
							//counter games per round being incremented in declare game over
							if (getCountGamesPerRound() < MAX_GAMES_PER_ROUND)
								fixDealerFirst();
							else {
								_cat.debug("ERROR!!!! only 3 games per table are allowed.");
								//determine the winner. trick is to keep the winner as active. rest get folded
								//winner is the one with the highest points.TBD
								int indexWon = -1;
								double max = 0;
								
								for (int z = 0; z < _players.length; z++){
									if (_players[z] != null){
										if (_players[z].getGameStartWorth() > max){
											max = _players[z].getGameStartWorth();
											indexWon = z;
											breakOutMatch = 0;
										}
										if (max != 0 && _players[z].getGameStartWorth() == max && indexWon != z){
											//need a break out match.
											breakOutMatch = 1;
										}
									}
								}
								
								if (breakOutMatch == 1){
									//the highest player has same chips as someone else, find them
									//only they will be active. rest will be marked broke
									indexWon = -1;
									for (int z = 0; z < _players.length; z++){
										if (_players[z] != null){
											if (_players[z].getGameStartWorth() != max)
												_players[z].setRUMMYStatus(status_BROKE);
											else {
												_players[z].setRUMMYStatus(status_ACTIVE);
												//give extra chips to players for frsh round
												_players[z].setGameStartWorth(80 + _players[z].getGameStartWorth());
											}
										}
									}
								}
								else if (indexWon == -1){
									//this is the case where all players are dead. give the prize to dealer
									//if dealer is not active, give the prize to first active player
									//if dealer is -1, give prize to anyone. it doesn't matter.
									if (_dealer != -1) {
										if(isActive(_players[_dealer])) {
											indexWon = _dealer;
											_cat.debug("DEALER ALL THE WAY");
										}
										else {
											indexWon = getNextActivePos(_dealer);
											_cat.debug("NEXT TO DEALER ALL THE WAY");
										}
									}
									else {
										indexWon = getNextActivePos(0);//start from 0. first active is the winner. ha!ha!
										_cat.debug("ANY ONE CAN WIN !!!!!");
									}
								}
								
								//check for error condition
								if (indexWon == -1){
									int count = getCntStatusActivePlayers();
									if (count == 1){
										indexWon = getOnlyActivePos();
									}
								}
								
								if (indexWon != -1){
									_cat.debug("PLAYER WHO WON HAS MAX : " + indexWon);
									for (int z = 0; z < _players.length; z++){
										if (_players[z] != null && z != indexWon){
											_players[z].setRUMMYStatus(status_BROKE);
										}
										//for each player, set the total worth
										if (_players[z] != null)
											_players[z].getPresence().setTotalWorth(_players[z].getGameStartWorth());
									}
									
									_roundWinnerPos = indexWon;
									setWinnerDeclared(1);
								}
								
								if (breakOutMatch == 1){
									_roundWinnerPos = -1;
									fixDealerFirst();
								}
							}
						}
					} else {
						// clear out the jokers for it creates a wrong
						// impression
						_rummyCardJoker = null;
						_discardCard = null;
						_dealer = -1;
						_rummyPlayer = -1;
						// remove the removed players now
						if (System.currentTimeMillis() - _nextMoveExpTime > 20000) {
							if (countPlayers == 1) {
								for (int m = 0; m < MAX_PLAYERS; m++) {
									if (_players[m] != null) {
										sendMessageKickedOut(m, 2);
										// remove the player
										removeFromTable(m);
//										_players[m] = null;
									}
								}
							} else {
								resetTable();
								_nextMoveExpTime = System.currentTimeMillis();
							}
						}

					}
				}

				if (!_gameOngoing) {
					if (System.currentTimeMillis() - _nextMoveExpTime > 120000) {
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
					Thread.currentThread().sleep(200);
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
				addCardsToFreshPile(_discardDeck, indexDiscardDeck);
				_discardDeck = new Card[65];

				// keep the same discard card - don't change it
				_deck.set(_discardCard.getIndex());

				indexDiscardDeck = 0;
				_discardDeck[indexDiscardDeck++] = _discardCard;

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
			// 0,0,-1,0,143680.64,-1,-1 //changed for tournament
			int tournyid = getTID(movedet); //this is the tourny id.
			int tid = getTableIndex(movedet);// table id, which table he is seated
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
			_cat.debug("tid : " + tid + " , pos : " + pos
					+ ", moveid : " + moveId + ",type : " + type + ", game ongoing : " + _gameOngoing
					+ " , nextmoveplayer : " + _nextMovePlayer + " , now playing on tourny : " + tournyid);

			// for tournament, we can't call this - all players sit on table
			// when tables are created
			// first handle the case of sit in request
			// if (moveId == move_RUMMY_JOINED) {
			// // definitely a sit in request ---
			// // check here if game is in declare mode expecting losers to
			// // send in their card string
			// // if so, we can't let this player join the table - he/she has
			// // to wait till dust settles
			// if (_nextMovePlayer != 111)
			// handleGameJoinReq(pos, p);
			// return;
			// }

			if (_players[pos] == null || _players[pos].getPresence() == null) {
				_cat.debug("wrong wrong wrong rummy profile or presence missing");
				return;
			}
			
			if (moveId == move_RUMMY_CHAT) {
				_lastMove = move_RUMMY_CHAT;
				_lastMovePos = pos;
				_lastMoveString = "Chat";
				chatOn = 1;
				chatMessage = _players[pos].pos() + ":" + cardsDet;
				broadcastMessage(-1);
				chatOn = 0;
				chatMessage = "";
				return;
			}

			if (!_gameOngoing) {
				_cat.debug("game not running, ignore all messages - let them all die!!!!!");
				return;
			}

			RummyProfile prp = _players[pos];

			if (_nextMovePlayer == 111 && moveId != move_RUMMY_DECLARE_LOSER) {
				_cat.debug("no moves other than declare loser allowed! on table " + _tid);
				sendErrorMessage(pos, 3);
				return;
			} else {
				if (_nextMovePlayer != 111 && pos != _nextMovePlayer
						&& moveId != move_RUMMY_DECLARE) {
					_cat.debug("no moves allowed from this pos!!! on table " + _tid);
					sendErrorMessage(pos, 0);
					return;
				}
				if ((moveId & _nextMovesAllowed) != moveId
						&& _nextMovePlayer != 111) {
					_cat.debug("these moves not allowed from this pos!! on table " + _tid);
					sendErrorMessage(pos, 0);
					return;
				}
			}

			_players[pos].rummyLeftTableTime = -1;

			// update the rummyLastMoveTime for this player
			_players[pos].rummyLastMoveTime = System.currentTimeMillis();
			
			if (moveId == move_RUMMY_DECLARE_LOSER) {
				_lastMove = move_RUMMY_DECLARE_LOSER;
				_lastMovePos = pos;
				_lastMoveString = "GameDeclareLoser";
				prp.setRUMMYMovesStr("&GameDeclareLoser^" + cardsDet);
				prp.setRUMMYMoveId(move_RUMMY_DECLARE_LOSER);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " declared.";

				// just store the cards arrangement string from player
				prp.cardsStrFromClient = cardsDet;

				countPlayerResponseDeclareLoser--;
				if (countPlayerResponseDeclareLoser <= 0) {
					// end the game here
					_winnerString = declareGameOver(idPlayerValidDeclared);
					_nextMovePlayer = -1;
					broadcastMessage(idPlayerValidDeclared);
					idPlayerValidDeclared = -1;
				}
			}

			if (moveId == move_RUMMY_DECLARE) {
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
				_discardDeck[indexDiscardDeck++] = _discardCard;
				
				if (result[0] == 0 && result[1] == 1)
					flagGameOver = true;

				if (flagGameOver) {
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " made valid declare.";
					
					_lastMoveString += "Valid:Winner";
					prp.setRUMMYMovesStr("^ValidDeclare");
					idPlayerValidDeclared = pos;
					// for each player, now set the move as declare loser. now
					// keep a count of players from whom messages have to come
					_nextMovesAllowed = move_RUMMY_DECLARE_LOSER;
					_nextMovePlayer = 111;
					broadcastMessage(-1);

					_nextMoveExpTime = System.currentTimeMillis();

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
						_nextMovePlayer = -1;
						broadcastMessage(idPlayerValidDeclared);
						idPlayerValidDeclared = -1;
					}

					return;
				} else {
					// apply penalty on player, fold him, continue game
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " made invalid declare.";
					
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
//				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_NEWCARD;
				_lastMovePos = pos;
				int typeO = 0;

				try {
					typeO = Integer.parseInt(type);
				} catch (NumberFormatException ex) {
					_cat.debug("wrong string for type!!!! : " + _tid);
					sendErrorMessage(pos, 1);
					return;
				}

				_cat.debug("reached here 3333333333333333333333333 "
						+ typeO + " , cards length : "
						+ prp._allotedCards.length + " on table : " + _tid);

				// if client sends this message again then 2nd and subsequent
				// message have to be dropped
				if (prp._allotedCards.length >= 14) {
					_cat.debug("already gave one card, how many does it want more !! on table : " + _tid);
					sendErrorMessage(pos, 0);
					return;
				}
				
				prp.setRUMMYMoveId(move_RUMMY_NEWCARD);
				prp.setRUMMYStatus(status_ACTIVE);
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
					chatMessage = "Pos " + (pos + 1) + " chose fresh card.";
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
							sendErrorMessage(pos, 0);
							return;
						}
					}
					
					_lastMoveString = "ChoseDiscardedCard";
					prp.setRUMMYMovesStr("&GetDisc^" + _discardCard.toString());
					
					//extra code here to increment the time bank counter
					//everytime player makes a move time bank is incremented by 1
					if (!prp.getUsingTimeBank())
						prp.incrTimeBank();
					else
						prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
					
					prp.setUsingTimeBank(false);
					
					_discardDeck[indexDiscardDeck - 1] = null;
					indexDiscardDeck--;
					if (indexDiscardDeck > 0)
						_discardCard = _discardDeck[indexDiscardDeck - 1];
					else
						_discardCard = null;

					newCardAdded = cr.toString();
					chatOn = 0;
					chatMessage = "Pos " + (pos + 1) + " chose discarded card.";
				}

				_cat.debug("came here : " + _lastMoveString
						+ " , card : " + cr.toString() + " on table : " + _tid);
				
				prp._firstMoveDone = true;

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
				_nextMoveExpTime = System.currentTimeMillis();
				_lastMove = move_RUMMY_DISCARD;
				_lastMovePos = pos;
				_lastMoveString = "DiscardCard:" + type;
				prp.setRUMMYStatus(status_ACTIVE);
				prp.setRUMMYMoveId(move_RUMMY_DISCARD);
				
				newCardAdded = "";

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
				_discardDeck[indexDiscardDeck++] = _discardCard;
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " discarded " + _discardCard.toString();
				
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
					
					_cat.debug("next move player : " + _nextMovePlayer + " on table : " + _tid);
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
				
				chatOn = 0;
				chatMessage = "Pos " + (pos + 1) + " folded.";
				
				_cat.debug("player " + _nextMovePlayer + " folded on table : " + _tid);

				if (prp._firstMoveDone)
					prp.setRUMMYPoints(40);
				else {
					prp.setRUMMYPoints(20);
					// player didn't even play one card, so put his cards in
					// fresh pile
					addCardsToFreshPile(prp._allotedCards, 13);
				}
				prp._allotedCards = null;
				
				//extra code here to increment the time bank counter
				//everytime player makes a move time bank is incremented by 1
				if (!prp.getUsingTimeBank())
					prp.incrTimeBank();
				else
					prp.setTimeBank(prp.getTimeBankExpTime() - System.currentTimeMillis());
				
				prp.setUsingTimeBank(false);

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
			
			_cat.debug("fixing dealer on table " + _tid);

			// now clear out the all in players list
			resetTable();
			
			_cardDealingOrder = "";

			if (fixingDealerNextHand) {

				_deck.clear();
				fixingDealerOngoing = true;

				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null
							&& isActive(_players[m])) {
						_players[m].fixPosCard = drawCardOneDeck();
					}
				}

				int least = 999, leastCardBearer = -1;
				for (int m = 0; m < MAX_PLAYERS; m++) {
					if (_players[m] != null
							&& _players[m].getPresence() != null
							&& isActive(_players[m])) {
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
				fixingDealerNextHand = false;
				countHandsAfterDealerFix = 0;
			} else {
				countHandsAfterDealerFix++;
				if (countHandsAfterDealerFix >= 4)
					fixingDealerNextHand = true;
			}

			startGameIfPossible();
		}

		public void startGameIfPossible() {
			if (_gameOngoing)
				return;

			// based on sitting players, let us see if we can start a game
			try {
				Thread.currentThread().sleep(500);
			} catch (InterruptedException ee) {
				// continue
			}

			// game can begin ---
			// now clear out the all in players list
			// resetTable();

			int _countPlayersInit = getCountActivePlayers();
			_cat.debug("startgame - " + _countPlayersInit);
			if (_countPlayersInit >= 2) {
				_gameOngoing = true;

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
					// && _players[m].getPresence() != null
							&& !isBroke(_players[m])
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
							&& _players[m].getPresence() != null) {
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

				broadcastMessage(-1);
				// sleep for 10 seconds to allow clients to distribute cards
				try {
					Thread.currentThread().sleep(5000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				_gameStartTime = Calendar.getInstance();
				_nextMovePlayer = _rummyPlayer;
				broadcastMessage(-1);
			} else {
				_cat.debug("changin flag in startgameposs : " + _gameOngoing);
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
			System.out.println("aeskey got " + aesKey);
			try {
	            Cipher cipher = Cipher.getInstance("AES");
	            System.out.println("cipher opbtained ... " + cipher);
	            // encrypt the text
	            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
	            byte[] encrypted = cipher.doFinal(text.getBytes());
//	            String str = new String(encrypted, "ISO-8859-1");
	            String str = Base64.encode(encrypted);
	            System.out.println("encryped : " + str);
	            return str;
			}
			catch(Exception ex){
				System.out.println("exception : " + ex.getMessage());
			}
			
			return null;
		}

		public void broadcastMessage(int winnerPos) {
			_cat.debug("broadcasting response!!!");
			StringBuffer temp = new StringBuffer();
			temp.append("RummyTournyServer=RummyTournyServer");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(_grid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);
			
			temp.append(",tournyid=").append(getTournyIndex());

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);
			
			temp.append(",chatMsgOn=").append(chatOn);
			if (!chatMessage.isEmpty()){
				String strcrypt = encrypt(chatMessage);
				temp.append(",chatMsg=").append(strcrypt);
//				System.out.println(decrypt(strcrypt));
				chatMessage = "";
			}
			else
				temp.append(",chatMsg=");
			
			if (_nextMovePlayer != -1 && _nextMovePlayer < MAX_PLAYERS && _players[_nextMovePlayer] != null && winnerPos == -1){
				//for time bank
				if (_players[_nextMovePlayer].getUsingTimeBank()){
					temp.append(",DiscProtOn=").append((_players[_nextMovePlayer].getTimeBank()) * 10);
				}
			}
			
			//for tournament
			temp.append(",RoundsPlayed=").append(countGamesPerRound);
			temp.append(",TournyPrize=").append(tablePrize);
			
			if (_roundWinnerPos != -1)
				temp.append(",OverAllWinner=").append(_roundWinnerPos);
			if (breakOutMatch == 1)
				temp.append(",TieBreak=1");
			
			temp.append(",tournystate=").append(_tablesTourny.get(getTournyIndex()).state);
			temp.append(",name=").append(_tablesTourny.get(getTournyIndex()).name);
			
			if (_rummyCardJoker != null)
				temp.append(",RummyJoker=").append(_rummyCardJoker.toString());
			else
				temp.append(",RummyJoker=");
			if (_discardCard != null && idPlayerValidDeclared == -1)
				temp.append(",DiscardCard=").append(_discardCard.toString());
			else
				temp.append(",DiscardCard=");

			if (idPlayerValidDeclared != -1) {
				temp.append(",ValidDecPlyr=").append(
						_players[idPlayerValidDeclared].name);
				temp.append(",ValidDecPlyrId=").append(idPlayerValidDeclared);
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
					if (_players[i] != null) {
						Card tempCr = new Card(_players[i].fixPosCard);
						str += i + "`" + tempCr.toString() + "'";
					}
				}
				str = str.substring(0, str.length() - 1);
				temp.append(",FixDealerProcess=").append(1);
				temp.append(",FixDealerCards=").append(str);
			}

			if (winnerPos != -1) {
				temp.append(",Winner=").append(winnerPos);
				String str = "";
				for (int k = 0; k < _players[winnerPos]._allotedCards.length; k++)
					str += _players[winnerPos]._allotedCards[k].toString()
							+ "'";
				str = str.substring(0, str.length() - 1);

				temp.append(",WinnerCards=").append(str);
				if (!_players[winnerPos].cardsStrFromClient.isEmpty())
					temp.append(",WinnerCardsString=").append(
							_players[winnerPos].cardsStrFromClient);

				temp.append(",Penalty=").append(_winnerString);
				temp.append(",WinPoints=").append(
						_players[winnerPos].getRUMMYWinAmt());
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
							+ _players[i].getGameStartWorth());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

//			_cat.debug("reaching here : temp : " + temp);

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
					//send message to actual players always
					//if message is for a bot who is next move player, send with cards of that bot.
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
						tempPlayerData.append(",PlayerWorth=" + _players[i].getGameStartWorth());
						
						_cat.debug("Message : " + tempPlayerData);
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
				//exception in message delivery. mark player as disconnected.
				pr.setDisconnected();
			}
			
			//check here if handler for the gameplayer of this player is disconnected. 
			//if so, mark player as disconnected for now no message will travel to player
			GamePlayer gp = (GamePlayer)pr.getPresence().player();
			if (gp.handler().isDisconnected())
				pr.setDisconnected();
		}

		public void sendMessageKickedOut(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("RummyTournyServer=RummyTournyServer");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);
			
			temp.append(",tournyid=").append(getTournyIndex());
			
			temp.append(",KickedOut=").append(prpos);
			
			temp.append(",RoundsPlayed=").append(countGamesPerRound);
			temp.append(",TournyPrize=").append(tablePrize);
			
			if (_roundWinnerPos != -1)
				temp.append(",OverAllWinner=").append(_roundWinnerPos);
			if (breakOutMatch == 1)
				temp.append(",TieBreak=1");
			
			temp.append(",tournystate=").append(_tablesTourny.get(getTournyIndex()).state);
			temp.append(",name=").append(_tablesTourny.get(getTournyIndex()).name);

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
							+ _players[i].getGameStartWorth());
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
			// for (int i = 0; i < MAX_PLAYERS; i++) {
			if (_players[prpos] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(prpos);
				tempPlayerData.append(",PlayerName=").append(_players[prpos].name);
				tempPlayerData.append(",PlayerWorth=" + _players[prpos].getGameStartWorth());
				
				sendMessage(tempPlayerData, _players[prpos]);
			}
			// }
		}

		public void sendErrorMessage(int prpos, int resCode) {
			StringBuffer temp = new StringBuffer();
			temp.append("RummyTournyServer=RummyTournyServer");
			temp.append(",gid=").append(_gid);
			temp.append(",grid=").append(rummygrid);
			temp.append(",rummygrid=").append(rummygrid);
			temp.append(",TID=").append(_tid);
			
			temp.append(",tournyid=").append(getTournyIndex());

			// for reason
			if (resCode == 0) {
				temp.append(",MsgDropped=").append("WrongMove");
			} else if (resCode == 1) {
				temp.append(",MsgDropped=").append("WrongCard");
			} else if (resCode == 3) {
				temp.append(",MsgDropped=").append("whoareyoukidding");
			} else if (resCode == 4) {
				temp.append(",MsgDropped=").append("nowayyoucantdothat");
			} else {
				temp.append(",MsgDropped=GetLost");
			}

			temp.append(",Dealer=").append(_dealer);
			temp.append(",RummyPlayer=").append(_rummyPlayer);
			temp.append(",GameOn=").append(_gameOngoing);
			
			temp.append(",RoundsPlayed=").append(countGamesPerRound);
			temp.append(",TournyPrize=").append(tablePrize);
			
			if (_roundWinnerPos != -1)
				temp.append(",OverAllWinner=").append(_roundWinnerPos);
			if (breakOutMatch == 1)
				temp.append(",TieBreak=1");
			
			temp.append(",tournystate=").append(_tablesTourny.get(getTournyIndex()).state);
			temp.append(",name=").append(_tablesTourny.get(getTournyIndex()).name);

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
							+ _players[i].getGameStartWorth());
				}
			}
			temp.append(",PlayerDetails=" + nCount + tempPlayerDet);

			// for each presence, call sendMessage with their individual data
			int i = prpos;
			if (_players[i] != null) {
				StringBuffer tempPlayerData = new StringBuffer(temp);
				tempPlayerData.append(",PlayerPos=").append(i);
				tempPlayerData.append(",PlayerName=").append(_players[i].name);
				
				if (_players[i]._allotedCards != null && !fixingDealerOngoing) {
					String str = "";
					for (int k = 0; k < _players[i]._allotedCards.length; k++)
						str += _players[i]._allotedCards[k].toString() + "'";
					str = str.substring(0, str.length() - 1);
					tempPlayerData.append(",Cards=" + str);
				} else
					tempPlayerData.append(",Cards=");

				tempPlayerData.append(",NewCardAdded=" + newCardAdded);
				
				tempPlayerData.append(",PlayerWorth=" + _players[i].getGameStartWorth());
				
				sendMessage(tempPlayerData, _players[i]);
			}
		}
	}

	//tournament id
	private int getTID(String movedet) {

		String[] betString = movedet.split(",");
		String tid = betString[0];
		return Integer.parseInt(tid);
	}
	
	//actual table id
	private int getTableIndex(String movedet) {

		String[] betString = movedet.split(",");
		String index = betString[1];
		return Integer.parseInt(index);
	}

	private int getPos(String movedet) {

		String[] betString = movedet.split(",");
		String pos = betString[2];
		return Integer.parseInt(pos);
	}

	private int getMoveId(String movedet) {

		String[] betString = movedet.split(",");
		String moveid = betString[3];
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
		String amt = betString[4];
		return amt;
	}

	// getCardsDet
	private String getCardsDet(String movedet) {

		String[] betString = movedet.split(",");
		String amt = betString[5];
		return amt;
	}

	public StringBuffer processMove(Player.Presence p, String movedet) {
		// deliberately keeping it empty, logic moved to rummyMove()
		return null;
	}

//	public int findNextInValidTable() {
//		for (int i = 0; i < _tablesTourny.size(); i++) {
//			// if (!tables[i].validTable)
//			return i;
//		}
//		return -1;
//	}

	// for tournaments, no one should call this
	public StringBuffer rummySitIn(Player.Presence p, String movedet) {
		// 1,-1,0,99915.35
		int tourneyId = getTID(movedet);// tourney id, which table he wants to be seated
		int tableIndex = getTableIndex(movedet);
		int moveId = getMoveId(movedet);

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("RummyTournyServer game - sit in req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			return null;
		}
		if (_tablesTourny.get(tourneyId) == null || 
				((_tablesTourny.get(tourneyId).state > START_PENDING) && (_tablesTourny.get(tourneyId).tables == null || 
				tableIndex >= _tablesTourny.get(tourneyId).tables.length || tableIndex < 0))) {
			buf.append("RummyTournyServer=RummyTournyServer,grid=")
					.append(-1)
					.append(",TourneyId=").append(tourneyId)
					.append(",TID=")
					.append(tableIndex)
					.append(",MsgDropped=InvalidTable,player-details="
							+ p.name() + "|" + p.netWorth());
			return buf;
		}

		int origPos = -1;
		int origTable = -1;
		if (_tablesTourny.get(tourneyId).tables != null){
			for (int m = 0; m < _tablesTourny.get(tourneyId).tables.length; m++){
				for (int k = 0; k < _tablesTourny.get(tourneyId).tables[m]._players.length; k++){
					if (_tablesTourny.get(tourneyId).tables[m]._players[k] != null &&
							p.name().compareTo(_tablesTourny.get(tourneyId).tables[m]._players[k].name) == 0){
						origPos = k;
						origTable = m;
						break;
					}
				}
				
				if (origPos != -1 && origTable != -1)
					break;
			}
		}
		
		_cat.debug("RUMMYSITIN : pos : " + origPos + " on table : " + origTable + " on tourney : " + tourneyId);
		
		if (moveId == 0) {
			// just wants to observe, it is ok
			// put a conditon over here for the plaeyrs who want to come back
			// they had placed some bets and are still valid players on the
			// table
			if (origPos != -1) {
				// found him, seated already.
				_cat.debug("found the player on one of the tables. send him there as a player");
				_tablesTourny.get(tourneyId).sendTournyUpdates(p, "TOURNEYDET=");
				return null;
			} else {
				// create a new rummyprofile for this presence
				RummyProfile kp = new RummyProfile();
				kp.setName(p.name());
				kp.setGameStartWorth(0);//start worth is 0 in the beginning.
				kp.setPresence(p);
				p.setKPIndex(_tablesTourny.get(tourneyId).tables[tableIndex].addObserver(kp));
				kp.setRUMMYStatus(0);
				kp.rummyLastMoveTime = System.currentTimeMillis();
				buf.append("RummyTournyServer=RummyTournyServer,grid=")
						.append(-1)
						.append(",TourneyId=").append(tourneyId)
						.append(",TID=")
						.append(tableIndex)
						.append(",player-details=");

				String strr = "";
				int nCount = 0;
				RummyTable rt = _tablesTourny.get(tourneyId).tables[tableIndex];
				
				for (int m = 0; m < rt._players.length; m++){
					if (rt._players[m] != null){
						nCount++;
						strr += ("'" + m + ":" + rt._players[m].getName()
								+ ":") + (rt._players[m].getRUMMYStatus() + ":"
								+ rt._players[m].getPresence().getGameStartWorth());
					}
				}
				
				buf.append(nCount + strr);
				
				_cat.debug("adding observor : " + p.name());
				return buf;
			}
		}
		else {
			//also replace the presence in holderregistered so that player gets his win amt
			if (_tablesTourny.get(tourneyId).holderRegisteredPlayers != null) {
				for (int m = 0; m < _tablesTourny.get(tourneyId).holderRegisteredPlayers.size(); m++){
					RummyProfile pp = (RummyProfile)_tablesTourny.get(tourneyId).holderRegisteredPlayers.get(m);
					if (pp.getName().compareToIgnoreCase(p.name()) == 0){
						pp.setPresence(p);
						break;
					}
				}
			}
			else {
				for (int m = 0; m < _tablesTourny.get(tourneyId).registeredPlayers.size(); m++){
					Presence pp = (Presence)_tablesTourny.get(tourneyId).registeredPlayers.get(m);
					if (pp.name().compareToIgnoreCase(p.name()) == 0){
						_tablesTourny.get(tourneyId).registeredPlayers.remove(m);
						_tablesTourny.get(tourneyId).registeredPlayers.add(p);
						break;
					}
				}
			}
			//so player wants to rejoin. check if origpos is not -1
			if (origPos != -1 && origTable != -1){
				//found the player on table. now we need to switch the presence
				_tablesTourny.get(tourneyId).tables[origTable]._players[origPos].setPresence(p);
				
				if (!_tablesTourny.get(tourneyId).tables[origTable].isBroke(_tablesTourny.get(tourneyId).tables[origTable]._players[origPos])
						&& !_tablesTourny.get(tourneyId).tables[origTable].isFolded(_tablesTourny.get(tourneyId).tables[origTable]._players[origPos])
						&& _tablesTourny.get(tourneyId).tables[origTable]._players[origPos]._allotedCards != null
						) {
					_tablesTourny.get(tourneyId).tables[origTable]._players[origPos].setRUMMYStatus(status_ACTIVE);
					_tablesTourny.get(tourneyId).tables[origTable]._players[origPos].resetDisconnected();
				}
			}
		}

		//no one can sit as a player. return null.
		buf.append("RummyTournyServer=RummyTournyServer,grid=")
		.append(-1)
		.append(",TourneyId=").append(tourneyId)
		.append(",TID=")
		.append(tableIndex)
		.append(",MsgDropped=TourneyOnlyObs,player-details="
				+ p.name() + "|" + p.netWorth());
		return buf;
	}

	// the moves sent by player will reach this method. it sends them to the
	// correct table
	public StringBuffer rummyMove(Player.Presence p, String movedet) {
		int tid = getTID(movedet);// tourny id now.

		StringBuffer buf = new StringBuffer();// for response back to player
		_cat.debug("RummyTournyServer game - move req --" + movedet);

		if (movedet == null || movedet.equals("null")) {
			buf.append("RummyTournyServer=RummyTournyServer,grid=")
					.append(-1)
					.append(",MsgDropped=MsgDropped,player-details=" + p.name()
							+ "|" + p.netWorth());
			return buf;
		}
//		if (tid >= MAX_TABLES) {
//			buf.append("RummyTournyServer=RummyTournyServer,grid=")
//					.append(-1)
//					.append(",MsgDropped=InvalidTable,player-details="
//							+ p.name() + "|" + p.netWorth());
//			return buf;
//		}

		// send this message to the table
		p.setLatestTournyMoveStr(movedet);
		addMT(tid, p);
		return null;
	}

	public StringBuffer rummyTablesList(Player.Presence p, String mdDet) {
		return gameDetail();
	}

	public void leave(Presence p) {
		// first get tourny id
		if (p == null)
			return;
		
		int tournyId = p.getRUMMYTournyTID();
		if (tournyId < 0)
			return;// wrong tourny id

		int tid = p.getRUMMYTID();
		if (tid == -1)
			return;// no need to do anything, already cleared this player out
		
		if (_tablesTourny == null)
			return;
		
		if (_tablesTourny.get(tournyId) == null)
			return;//tourney already destroyed
//		_cat.debug("tournyid valid in tablestourny : " + _tablesTourny.get(tournyId).state);
		
		if (_tablesTourny.get(tournyId).state < 1 || _tablesTourny.get(tournyId).state > 3)
			return;
//		_cat.debug("tourny is not dead! there could be players in there! help them! save them!");
		
		if (_tablesTourny.get(tournyId).state == 3){
			if (_tablesTourny.get(tournyId).tables == null)
				return;//all tables already destroyed
//			_cat.debug("tables not null in tablestourny tournyid");
	
			if (_tablesTourny.get(tournyId).tables[tid] == null)
				return;//table already destroyed.
//			_cat.debug("table not null in tables of tablestourny tournyid");
			
			RummyProfile kp = _tablesTourny.get(tournyId).tables[tid].findProfile(p);
			if (kp == null) {
//				_cat.debug("kp null");
				int obs = _tablesTourny.get(tournyId).tables[tid].findObsPos(p);
//				_cat.debug("obs value : " + obs);
				if (obs > -1) {
					kp = _tablesTourny.get(tournyId).tables[tid].findObservor(obs);
					if (kp != null) {
						_tablesTourny.get(tournyId).tables[tid].removeObserver(kp);
//						_cat.debug("removing observor");
					}
				}
				return;
			}
	
			if (_tablesTourny.get(tournyId).tables[tid].isLeft(kp))
				_cat.debug("kp already left");
			if (_tablesTourny.get(tournyId).tables[tid].isRemoved(kp))
				_cat.debug("kp already removed");
			
			if (_tablesTourny.get(tournyId).tables[tid].isLeft(kp) || _tablesTourny.get(tournyId).tables[tid].isRemoved(kp)) {
				return;
			}
	
			// just mark the player as left. on completion of cycle, the player will
			// be removed
			kp.rummyLeftTableTime = System.currentTimeMillis();
			kp.setRUMMYMovesStr("&Leave");
			kp.setRUMMYMoveId(move_RUMMY_LEFT);
			kp.setRUMMYStatus(status_REMOVED);
			_tablesTourny.get(tournyId).tables[tid].handleMoveNoAction(kp.pos());
			_cat.debug("take action plyr left : " + kp.pos()
					+ " from table : " + tid);
		}
		
		//check if this player is in tourneyObservors list
		if (_tablesTourny.get(tournyId).tourneyObservors.contains(p))
			_tablesTourny.get(tournyId).tourneyObservors.remove(p);
		
		_cat.debug("returning from leave...");
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		//check db if some new tournament has been added
		//can't check after every 500 ms. 
		while (_keepChkDB){
			String actualResFromDB = "";
			String result = DBPlayer.getListOfRummyTournys();
			if (!result.isEmpty() && result.compareToIgnoreCase(listOfTourneys) != 0){
	
				//so there are some new entries
				String[] newToks = result.split("\\|");
				for (int i = 0; i < newToks.length; i++){
					boolean flag = StringUtils.contains(listOfTourneys, newToks[i]);
					if (!flag){
						actualResFromDB += newToks[i] + "|";
					}
				}
				
				//only for newly added tourneys
				String[] resToks = actualResFromDB.split("\\|");
				
				int newTournysAdded = resToks.length;
				
				//new tourneys to be added
				for (int i = 0; i < newTournysAdded; i++) {
					_msgQTourny.add(new LinkedList());
				}
				
				int alreadyRunningTourneys = _tablesTourny.size();
				
				for (int i = 0; i < newTournysAdded; i++) {
					String[] resToks2 = resToks[i].split("\\'");
					
					RummyTournyTable rtt = new RummyTournyTable();
					rtt.state = CREATED;
					
					//so that we start a tourny after every 1 minutes
					rtt.timeCreated = System.currentTimeMillis() + (60000 * 1 * i);
					rtt.createdTournyTime = Calendar.getInstance();
					
					rtt.fees = Double.parseDouble(resToks2[2]);
					
					rtt.toReplicate = Integer.parseInt(resToks2[1]) > 0 ? false : true;
					
					rtt.name = resToks2[0];
					
					rtt.setIndex(alreadyRunningTourneys + i);
					
					_tablesTourny.add(rtt);
					
					//TBD - resToks2[3] holds the game state. if it is > 0, then it means valid tournys else don't spawn them.
					
					Thread t = new Thread(rtt);
					t.setName("Rummy-Tourny-" + (alreadyRunningTourneys + i));
					t.setPriority(Thread.NORM_PRIORITY);
					t.start();
	
					_cat.debug("keepcheckdb --- Rummy Tournament Server starting thread for tournament : " + i + " with name " + rtt.name);
				}
				listOfTourneys = result;
				MAX_TOURNY = newToks.length;
				
			}
			
			// now sleep for some time - 30 minutes. in the long run, i see it as 10 minutes thing.
			try {
				Thread.currentThread().sleep(30 * 60 * 1000);
			} catch (InterruptedException ee) {
				// continue
			}
		}
	}
}
