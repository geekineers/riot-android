/*
 * Copyright 2016 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.TextView;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.store.IMXStore;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import im.vector.Matrix;
import im.vector.R;
import im.vector.activity.CommonActivityUtils;
import im.vector.contacts.Contact;
import im.vector.contacts.ContactsManager;
import im.vector.contacts.PIDsRetriever;
import im.vector.util.VectorUtils;

/**
 * This class displays the users search results list.
 * The first list row can be customized.
 */
public class VectorParticipantsAdapter extends BaseExpandableListAdapter {

    private static final String LOG_TAG = "VectorAddPartsAdapt";

    private static final String KEY_EXPAND_STATE_SEARCH_LOCAL_CONTACTS_GROUP = "KEY_EXPAND_STATE_SEARCH_LOCAL_CONTACTS_GROUP";
    private static final String KEY_EXPAND_STATE_SEARCH_MATRIX_CONTACTS_GROUP = "KEY_EXPAND_STATE_SEARCH_MATRIX_CONTACTS_GROUP";


    // search events listener
    public interface OnParticipantsSearchListener {
        /**
         * The search is ended.
         *
         * @param count the number of matched user
         */
        void onSearchEnd(int count);
    }

    // layout info
    private final Context mContext;
    private final LayoutInflater mLayoutInflater;

    // account info
    private final MXSession mSession;
    private final String mRoomId;

    // used layouts
    private final int mCellLayoutResourceId;
    private final int mHeaderLayoutResourceId;

    // participants list
    private List<ParticipantAdapterItem> mUnusedParticipants = null;
    private List<ParticipantAdapterItem> mContactsParticipants = null;
    private List<String> mUsedMemberUserIds = null;
    private List<String> mDisplayNamesList = null;
    private String mPattern = "";

    private List<ParticipantAdapterItem> mItemsToHide = new ArrayList<>();

    // way to detect that the contacts list has been updated
    private int mLocalContactsSnapshotSession = -1;

    // the participant sort method
    private final Comparator<ParticipantAdapterItem> mSortMethod = new Comparator<ParticipantAdapterItem>() {
        /**
         * Compare 2 string and returns sort order.
         * @param s1 string 1.
         * @param s2 string 2.
         * @return the sort order.
         */
        private int alphaComparator(String s1, String s2) {
            if (s1 == null) {
                return -1;
            } else if (s2 == null) {
                return 1;
            }

            return String.CASE_INSENSITIVE_ORDER.compare(s1, s2);
        }

        @Override
        public int compare(ParticipantAdapterItem part1, ParticipantAdapterItem part2) {
            User userA = mSession.getDataHandler().getUser(part1.mUserId);
            User userB = mSession.getDataHandler().getUser(part2.mUserId);

            String userADisplayName = part1.getComparisonDisplayName();
            String userBDisplayName = part2.getComparisonDisplayName();

            boolean isUserA_Active = false;
            boolean isUserB_Active = false;

            if ((null != userA) && (null != userA.currently_active)) {
                isUserA_Active = userA.currently_active;
            }

            if ((null != userB) && (null != userB.currently_active)) {
                isUserB_Active = userB.currently_active;
            }

            if ((null == userA) && (null == userB)) {
                return alphaComparator(userADisplayName, userBDisplayName);
            } else if ((null != userA) && (null == userB)) {
                return +1;
            } else if ((null == userA) && (null != userB)) {
                return -1;
            } else if (isUserA_Active && isUserB_Active) {
                return alphaComparator(userADisplayName, userBDisplayName);
            }

            if (isUserA_Active && !isUserB_Active) {
                return -1;
            }
            if (!isUserA_Active && isUserB_Active) {
                return +1;
            }

            // Finally, compare the timestamps
            long lastActiveAgoA = (null != userA) ? userA.getAbsoluteLastActiveAgo() : 0;
            long lastActiveAgoB = (null != userB) ? userB.getAbsoluteLastActiveAgo() : 0;

            long diff = lastActiveAgoA - lastActiveAgoB;

            if (diff == 0) {
                return alphaComparator(userADisplayName, userBDisplayName);
            }

            // if only one member has a lastActiveAgo, prefer it
            if (0 == lastActiveAgoA) {
                return +1;
            } else if (0 == lastActiveAgoB) {
                return -1;
            }

            return (diff > 0) ? +1 : -1;
        }
    };

    // define the first entry to set
    private ParticipantAdapterItem mFirstEntry;

    // the participants can be split in sections
    private final List<List<ParticipantAdapterItem>> mParticipantsListsList = new ArrayList<>();
    private int mFirstEntryPosition = -1;
    private int mLocalContactsSectionPosition = -1;
    private int mRoomContactsSectionPosition = -1;

    // flag specifying if we show all peoples or only ones having a matrix user id
    private boolean mShowMatrixUserOnly = false;

    // Set to true when we need to display the "+" icon
    private boolean mWithAddIcon;

    /**
     * Create a room member adapter.
     * If a room id is defined, the adapter is in edition mode : the user can add / remove dynamically members or leave the room.
     * If there is none, the room is in creation mode : the user can add/remove members to create a new room.
     *
     * @param context                the context.
     * @param cellLayoutResourceId   the cell layout.
     * @param headerLayoutResourceId the header layout
     * @param session                the session.
     * @param roomId                 the room id.
     * @param withAddIcon            whether we need to display the "+" icon
     */
    public VectorParticipantsAdapter(Context context, int cellLayoutResourceId, int headerLayoutResourceId, MXSession session, String roomId, boolean withAddIcon) {
        mContext = context;

        mLayoutInflater = LayoutInflater.from(context);
        mCellLayoutResourceId = cellLayoutResourceId;
        mHeaderLayoutResourceId = headerLayoutResourceId;

        mSession = session;
        mRoomId = roomId;
        mWithAddIcon = withAddIcon;
    }

    /**
     * Reset the adapter content
     */
    public void reset() {
        mParticipantsListsList.clear();
        mFirstEntryPosition = -1;
        mLocalContactsSectionPosition = -1;
        mRoomContactsSectionPosition = -1;
        mPattern = null;

        notifyDataSetChanged();
    }

    /**
     * Search a pattern in the known members list.
     *
     * @param pattern        the pattern to search
     * @param firstEntry     the entry to display in the results list.
     * @param searchListener the search result listener
     */
    public void setSearchedPattern(String pattern, ParticipantAdapterItem firstEntry, OnParticipantsSearchListener searchListener) {
        if (null == pattern) {
            pattern = "";
        } else {
            pattern = pattern.toLowerCase().trim().toLowerCase();
        }

        if (!pattern.equals(mPattern) || TextUtils.isEmpty(mPattern)) {
            mPattern = pattern;
            refresh(firstEntry, searchListener);
        } else if (null != searchListener) {
            int displayedItemsCount = 0;

            for (List<ParticipantAdapterItem> list : mParticipantsListsList) {
                displayedItemsCount += list.size();
            }

            searchListener.onSearchEnd(displayedItemsCount);
        }
    }

    private static final Pattern FACEBOOK_EMAIL_ADDRESS = Pattern.compile("[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}\\@facebook.com");
    private static final List<Pattern> mBlackedListEmails = Arrays.asList(FACEBOOK_EMAIL_ADDRESS);

    /**
     * Tells if an email is black-listed
     *
     * @param email the email address to test.
     * @return true if the email address is black-listed
     */
    private static boolean isBlackedListed(String email) {
        for (int i = 0; i < mBlackedListEmails.size(); i++) {
            if (mBlackedListEmails.get(i).matcher(email).matches()) {
                return true;
            }
        }

        return !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }

    /**
     * Add the contacts participants
     *
     * @param list the participantItem indexed by their matrix Id
     */
    private void addContacts(List<ParticipantAdapterItem> list) {
        Collection<Contact> contacts = ContactsManager.getLocalContactsSnapshot();

        if (null != contacts) {
            for (Contact contact : contacts) {
                for (String email : contact.getEmails()) {
                    if (!TextUtils.isEmpty(email) && !isBlackedListed(email)) {
                        Contact dummyContact = new Contact(email);
                        dummyContact.setDisplayName(contact.getDisplayName());
                        dummyContact.addEmailAdress(email);
                        dummyContact.setThumbnailUri(contact.getThumbnailUri());

                        ParticipantAdapterItem participant = new ParticipantAdapterItem(dummyContact);

                        Contact.MXID mxid = PIDsRetriever.getInstance().getMXID(email);

                        if (null != mxid) {
                            participant.mUserId = mxid.mMatrixId;
                        } else {
                            participant.mUserId = email;
                        }

                        if (mUsedMemberUserIds != null && !mUsedMemberUserIds.contains(participant.mUserId)) {
                            list.add(participant);
                        }
                    }
                }
            }
        }
    }

    private void fillUsedMembersList() {
        IMXStore store = mSession.getDataHandler().getStore();

        // Used members (ids) which should be removed from the final list
        mUsedMemberUserIds = new ArrayList<>();

        // Add members of the given room to the used members list (when inviting to existing room)
        if ((null != mRoomId) && (null != store)) {
            Room fromRoom = store.getRoom(mRoomId);

            if (null != fromRoom) {
                Collection<RoomMember> members = fromRoom.getLiveState().getDisplayableMembers();
                for (RoomMember member : members) {
                    if (TextUtils.equals(member.membership, RoomMember.MEMBERSHIP_JOIN) || TextUtils.equals(member.membership, RoomMember.MEMBERSHIP_INVITE)) {
                        mUsedMemberUserIds.add(member.getUserId());
                    }
                }
            }
        }

        // Add participants to hide to the used members list (when creating a new room)
        for (ParticipantAdapterItem item : mItemsToHide) {
            mUsedMemberUserIds.add(item.mUserId);
        }
    }

    /**
     * Refresh the un-invited members
     */
    private void listOtherMembers() {
        fillUsedMembersList();

        mUnusedParticipants = new ArrayList<>();
        // Add all known matrix users
        mUnusedParticipants.addAll(VectorUtils.listKnownParticipants(mSession).values());
        // Add phone contacts which have an email address
        addContacts(mUnusedParticipants);

        // List of display names
        mDisplayNamesList = new ArrayList<>();

        for (Iterator<ParticipantAdapterItem> iterator = mUnusedParticipants.iterator(); iterator.hasNext(); ) {
            ParticipantAdapterItem item = iterator.next();
            if (!mUsedMemberUserIds.isEmpty() && mUsedMemberUserIds.contains(item.mUserId)) {
                // Remove the used members from the final list
                iterator.remove();
            } else if (!TextUtils.isEmpty(item.mDisplayName)) {
                // Add to the display names list
                mDisplayNamesList.add(item.mDisplayName.toLowerCase());
            }
        }
    }

    /**
     * @return true if the known members list has been initialized.
     */
    public boolean isKnownMembersInitialized() {
        return null != mDisplayNamesList;
    }

    /**
     * Tells an item fullfill the search method.
     *
     * @param item    the item to test
     * @param pattern the pattern
     * @return true if match the search method
     */
    private static boolean match(ParticipantAdapterItem item, String pattern) {
        return item.startsWith(pattern);
    }

    /**
     * Some contacts pids have been updated.
     */
    public void onPIdsUpdate() {
        boolean gotUpdates = false;

        if (null != mUnusedParticipants) {
            for (ParticipantAdapterItem item : mUnusedParticipants) {
                gotUpdates |= item.retrievePids();
            }
        }

        if (null != mContactsParticipants) {
            for (ParticipantAdapterItem item : mContactsParticipants) {
                gotUpdates |= item.retrievePids();
            }

            // Check whether we need to remove the contact section
            if (mContactsParticipants.isEmpty() && mLocalContactsSectionPosition != -1) {
                mParticipantsListsList.remove(mLocalContactsSectionPosition);
                mLocalContactsSectionPosition = -1;
                mRoomContactsSectionPosition--;
                notifyDataSetChanged();
            }
        }

        if (gotUpdates) {
            refresh(mFirstEntry, null);
        }
    }

    /**
     * Check if some entries use the same matrix ids.
     * If some use the same, prefer the room member one.
     *
     * @return true if some duplicated entries have been removed.
     */
    private boolean checkDuplicatedMatrixIds() {
        boolean gotDuplicated = false;

        if ((mRoomContactsSectionPosition >= 0) && (mLocalContactsSectionPosition >= 0)) {
            // if several entries have the same matrix id.
            // keep the dedicated contact book entry over the room participants
            ArrayList<String> matrixUserIds = new ArrayList<>();

            List<ParticipantAdapterItem> contactParticipants = mParticipantsListsList.get(mLocalContactsSectionPosition);

            for (ParticipantAdapterItem item : contactParticipants) {
                if (!TextUtils.isEmpty(item.mUserId)) {
                    matrixUserIds.add(item.mUserId);
                }
            }

            ArrayList<ParticipantAdapterItem> itemsToRemove = new ArrayList<>();
            List<ParticipantAdapterItem> roomParticipants = mParticipantsListsList.get(mRoomContactsSectionPosition);
            for (ParticipantAdapterItem item : roomParticipants) {
                // if the entry is duplicated and comes from a contact
                if (matrixUserIds.contains(item.mUserId)) {
                    // add it to items list that will be removed
                    itemsToRemove.add(item);
                }
            }

            if (itemsToRemove.size() > 0) {
                roomParticipants.removeAll(itemsToRemove);
                gotDuplicated = true;

                if (roomParticipants.size() == 0) {
                    mParticipantsListsList.remove(roomParticipants);
                    mRoomContactsSectionPosition = -1;
                    // assume that the participants are displayed after the contacts
                }
            }

        }

        return gotDuplicated;
    }

    /**
     * Defines a set of participant items to hide.
     *
     * @param itemsToHide the set to hide
     */
    public void setHiddenParticipantItems(List<ParticipantAdapterItem> itemsToHide) {
        if (null != itemsToHide) {
            mItemsToHide = itemsToHide;
        }
    }

    /**
     * Refresh the display.
     *
     * @param theFirstEntry  the first entry in the result.
     * @param searchListener the search result listener
     */
    private void refresh(final ParticipantAdapterItem theFirstEntry, final OnParticipantsSearchListener searchListener) {
        if (!mSession.isAlive()) {
            Log.e(LOG_TAG, "refresh : the session is not anymore active");
            return;
        }

        // test if the local contacts list has been cleared (while putting the application in background)
        if (mLocalContactsSnapshotSession != ContactsManager.getLocalContactsSnapshotSession()) {
            mUnusedParticipants = null;
            mContactsParticipants = null;
            mUsedMemberUserIds = null;
            mDisplayNamesList = null;
            mLocalContactsSnapshotSession = ContactsManager.getLocalContactsSnapshotSession();
        }

        List<ParticipantAdapterItem> participantItemList = new ArrayList<>();

        // displays something only if there is a pattern
        if (!TextUtils.isEmpty(mPattern)) {
            // the list members are refreshed in background to avoid UI locks
            if (null == mUnusedParticipants) {
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        // populate full contact list
                        listOtherMembers();

                        Handler handler = new Handler(Looper.getMainLooper());

                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                refresh(theFirstEntry, searchListener);
                            }
                        });
                    }
                });

                t.setPriority(Thread.MIN_PRIORITY);
                t.start();

                return;
            }

            for (ParticipantAdapterItem item : mUnusedParticipants) {
                if (match(item, mPattern)) {
                    participantItemList.add(item);
                }
            }
        } else {
            resetGroupExpansionPreferences();

            // display only the contacts
            if (null == mContactsParticipants) {
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        fillUsedMembersList();

                        List<ParticipantAdapterItem> list = new ArrayList<>();
                        addContacts(list);
                        mContactsParticipants = list;

                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                refresh(theFirstEntry, searchListener);
                            }
                        });
                    }
                });

                t.setPriority(Thread.MIN_PRIORITY);
                t.start();

                return;
            } else {
                for (Iterator<ParticipantAdapterItem> iterator = mContactsParticipants.iterator(); iterator.hasNext(); ) {
                    ParticipantAdapterItem item = iterator.next();
                    if (!mUsedMemberUserIds.isEmpty() && mUsedMemberUserIds.contains(item.mUserId)) {
                        // Remove the used members from the contact list
                        iterator.remove();
                    }
                }
            }

            participantItemList.addAll(mContactsParticipants);
        }

        // ensure that the PIDs have been retrieved
        // it might have failed
        ContactsManager.retrievePids();

        // the caller defines a first entry to display
        ParticipantAdapterItem firstEntry = theFirstEntry;

        // detect if the user ID is defined in the known members list
        if ((null != mUsedMemberUserIds) && (null != firstEntry)) {
            if (mUsedMemberUserIds.indexOf(theFirstEntry.mUserId) >= 0) {
                firstEntry = null;
            }
        }

        if (null != firstEntry) {
            participantItemList.add(0, firstEntry);

            // avoid multiple definitions of the written email
            for (int pos = 1; pos < participantItemList.size(); pos++) {
                ParticipantAdapterItem item = participantItemList.get(pos);

                if (TextUtils.equals(item.mUserId, firstEntry.mUserId)) {
                    participantItemList.remove(pos);
                    break;
                }
            }

            mFirstEntry = firstEntry;
        } else {
            mFirstEntry = null;
        }

        // split the participants in sections
        List<ParticipantAdapterItem> firstEntryList = new ArrayList<>();
        List<ParticipantAdapterItem> contactBookList = new ArrayList<>();
        List<ParticipantAdapterItem> roomContactsList = new ArrayList<>();

        for (ParticipantAdapterItem item : participantItemList) {
            if (item == mFirstEntry) {
                firstEntryList.add(mFirstEntry);
            } else if (null != item.mContact) {
                if (!mShowMatrixUserOnly || !item.mContact.getMatrixIdMedias().isEmpty()) {
                    contactBookList.add(item);
                }
            } else {
                roomContactsList.add(item);
            }
        }

        mFirstEntryPosition = -1;
        mLocalContactsSectionPosition = -1;
        mRoomContactsSectionPosition = -1;

        int posCount = 0;

        mParticipantsListsList.clear();

        if (firstEntryList.size() > 0) {
            mParticipantsListsList.add(firstEntryList);
            mFirstEntryPosition = posCount;
            posCount++;
        }

        if (contactBookList.size() > 0) {
            // the contacts are sorted by alphabetical method
            Collections.sort(contactBookList, ParticipantAdapterItem.alphaComparator);
            mParticipantsListsList.add(contactBookList);
            mLocalContactsSectionPosition = posCount;
            posCount++;
        } else if (!ContactsManager.arePIDsRetrieved()) {
            // Contact lookup in progress, still display it with a loader
            mParticipantsListsList.add(contactBookList);
            mLocalContactsSectionPosition = posCount;
            posCount++;
        }

        if (roomContactsList.size() > 0) {
            // use th
            Collections.sort(roomContactsList, mSortMethod);
            mParticipantsListsList.add(roomContactsList);
            mRoomContactsSectionPosition = posCount;
            posCount++;
        }

        // keep contacts over the room participants
        checkDuplicatedMatrixIds();

        if (null != searchListener) {
            int length = 0;

            for (List<ParticipantAdapterItem> list : mParticipantsListsList) {
                length += list.size();
            }

            searchListener.onSearchEnd(length);
        }

        notifyDataSetChanged();
    }

    @Override
    public void onGroupCollapsed(int groupPosition) {
        super.onGroupCollapsed(groupPosition);
        setGroupExpandedStatus(groupPosition, false);
    }

    @Override
    public void onGroupExpanded(int groupPosition) {
        super.onGroupExpanded(groupPosition);
        setGroupExpandedStatus(groupPosition, true);
    }


    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        ParticipantAdapterItem item = (ParticipantAdapterItem) getChild(groupPosition, childPosition);
        return groupPosition != 0 || item.mIsValid;
    }


    @Override
    public int getGroupCount() {
        return mParticipantsListsList.size();
    }

    private String getGroupTitle(int position) {
        if (position == mLocalContactsSectionPosition) {
            return mContext.getString(R.string.people_search_contacts);
        } else if (position == mRoomContactsSectionPosition) {
            return mContext.getString(R.string.people_search_known_contacts);
        } else {
            return "??";
        }
    }

    @Override
    public Object getGroup(int groupPosition) {
        return getGroupTitle(groupPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
        return getGroupTitle(groupPosition).hashCode();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        if (groupPosition >= mParticipantsListsList.size()) {
            return 0;
        }

        return mParticipantsListsList.get(groupPosition).size();
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        if ((groupPosition < mParticipantsListsList.size()) && (groupPosition >= 0)) {
            List<ParticipantAdapterItem> list = mParticipantsListsList.get(groupPosition);

            if ((childPosition < list.size()) && (childPosition >= 0)) {
                return list.get(childPosition);
            }
        }
        return null;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        Object item = getChild(groupPosition, childPosition);

        if (null != item) {
            return item.hashCode();
        }

        return 0L;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        if (null == convertView) {
            convertView = this.mLayoutInflater.inflate(this.mHeaderLayoutResourceId, null);
        }

        TextView sectionNameTxtView = (TextView) convertView.findViewById(org.matrix.androidsdk.R.id.heading);

        if (null != sectionNameTxtView) {
            String title = getGroupTitle(groupPosition);

            if (!TextUtils.isEmpty(mPattern)) {
                title += " (" + mParticipantsListsList.get(groupPosition).size() + ")";
            }

            sectionNameTxtView.setText(title);
        }

        ImageView imageView = (ImageView) convertView.findViewById(org.matrix.androidsdk.R.id.heading_image);

        if (!isExpanded) {
            imageView.setImageResource(R.drawable.ic_material_expand_less_black);
        } else {
            imageView.setImageResource(R.drawable.ic_material_expand_more_black);
        }

        View subLayout = convertView.findViewById(R.id.people_header_sub_layout);
        subLayout.setVisibility((groupPosition == mFirstEntryPosition) ? View.GONE : View.VISIBLE);
        View loadingView = subLayout.findViewById(R.id.heading_loading_view);
        loadingView.setVisibility(groupPosition == mLocalContactsSectionPosition && !ContactsManager.arePIDsRetrieved() ? View.VISIBLE : View.GONE);

        if (parent instanceof ExpandableListView) {
            ExpandableListView expandableListView = (ExpandableListView) parent;
            boolean shouldBeExpanded = isGroupExpanded(groupPosition);

            if (expandableListView.isGroupExpanded(groupPosition) != shouldBeExpanded) {
                if (shouldBeExpanded) {
                    expandableListView.expandGroup(groupPosition);
                } else {
                    expandableListView.collapseGroup(groupPosition);
                }
            }
        }

        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(mCellLayoutResourceId, parent, false);
        }

        // bound checks
        if (groupPosition >= mParticipantsListsList.size()) {
            return convertView;
        }

        List<ParticipantAdapterItem> list = mParticipantsListsList.get(groupPosition);

        if (childPosition >= list.size()) {
            return convertView;
        }

        final ParticipantAdapterItem participant = list.get(childPosition);

        // retrieve the ui items
        final ImageView thumbView = (ImageView) convertView.findViewById(R.id.filtered_list_avatar);
        final TextView nameTextView = (TextView) convertView.findViewById(R.id.filtered_list_name);
        final TextView statusTextView = (TextView) convertView.findViewById(R.id.filtered_list_status);
        final ImageView matrixUserBadge = (ImageView) convertView.findViewById(R.id.filtered_list_matrix_user);

        // display the avatar
        participant.displayAvatar(mSession, thumbView);

        nameTextView.setText(participant.getUniqueDisplayName(mDisplayNamesList));

        // set the presence
        String status = "";

        if (groupPosition == mRoomContactsSectionPosition) {
            User user = null;
            MXSession matchedSession = null;
            // retrieve the linked user
            ArrayList<MXSession> sessions = Matrix.getMXSessions(mContext);

            for (MXSession session : sessions) {
                if (null == user) {
                    matchedSession = session;
                    user = session.getDataHandler().getUser(participant.mUserId);
                }
            }

            if (null != user) {
                status = VectorUtils.getUserOnlineStatus(mContext, matchedSession, participant.mUserId, new SimpleApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void info) {
                        VectorParticipantsAdapter.this.refresh(mFirstEntry, null);
                    }
                });
            }
        }

        // the contact defines a matrix user but there is no way to get more information (presence, avatar)
        if (participant.mContact != null) {
            boolean isMatrixUserId = !android.util.Patterns.EMAIL_ADDRESS.matcher(participant.mUserId).matches();
            matrixUserBadge.setVisibility(isMatrixUserId ? View.VISIBLE : View.GONE);
            statusTextView.setText(participant.mContact.getEmails().get(0));
        } else {
            statusTextView.setText(status);
            matrixUserBadge.setVisibility(View.GONE);
        }

        // Add alpha if cannot be invited
        convertView.setAlpha(participant.mIsValid ? 1f : 0.5f);

        // the checkbox is not managed here
        final CheckBox checkBox = (CheckBox) convertView.findViewById(R.id.filtered_list_checkbox);
        checkBox.setVisibility(View.GONE);

        final View addParticipantImageView = convertView.findViewById(R.id.filtered_list_add_button);
        addParticipantImageView.setVisibility(mWithAddIcon ? View.VISIBLE : View.GONE);

        return convertView;
    }

    /**
     * Reset the expansion preferences
     */
    private void resetGroupExpansionPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(KEY_EXPAND_STATE_SEARCH_LOCAL_CONTACTS_GROUP);
        editor.remove(KEY_EXPAND_STATE_SEARCH_MATRIX_CONTACTS_GROUP);
        editor.commit();
    }

    /**
     * Tells if a group is expanded
     *
     * @param groupPosition the group position
     * @return true to expand the group
     */
    private boolean isGroupExpanded(int groupPosition) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        if (groupPosition == mLocalContactsSectionPosition) {
            return preferences.getBoolean(KEY_EXPAND_STATE_SEARCH_LOCAL_CONTACTS_GROUP, CommonActivityUtils.GROUP_IS_EXPANDED);
        } else if (groupPosition == mRoomContactsSectionPosition) {
            return preferences.getBoolean(KEY_EXPAND_STATE_SEARCH_MATRIX_CONTACTS_GROUP, CommonActivityUtils.GROUP_IS_EXPANDED);
        }

        return true;
    }

    /**
     * Update the expanded group status
     *
     * @param groupPosition the group position
     * @param isExpanded    true if the group is expanded
     */
    private void setGroupExpandedStatus(int groupPosition, boolean isExpanded) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = preferences.edit();

        if (groupPosition == mLocalContactsSectionPosition) {
            editor.putBoolean(KEY_EXPAND_STATE_SEARCH_LOCAL_CONTACTS_GROUP, isExpanded);
        } else if (groupPosition == mRoomContactsSectionPosition) {
            editor.putBoolean(KEY_EXPAND_STATE_SEARCH_MATRIX_CONTACTS_GROUP, isExpanded);
        }

        editor.commit();
    }

    /**
     * Specify whether we show all contacts or only ones having a matrix user id
     *
     * @param matrixUserOnly
     */
    public void displayOnlyMatrixUsers(final boolean matrixUserOnly) {
        mShowMatrixUserOnly = matrixUserOnly;
        refresh(mFirstEntry, null);
    }
}
