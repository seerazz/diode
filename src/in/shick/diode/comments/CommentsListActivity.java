/*
 * Copyright 2009 Andrew Shu, 2012 veeti
 *
 * This file is part of "diode".
 *
 * "diode" is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * "diode" is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with "diode".  If not, see <http://www.gnu.org/licenses/>.
 */

package in.shick.diode.comments;


import android.text.SpannableStringBuilder;
import in.shick.diode.R;
import in.shick.diode.common.CacheInfo;
import in.shick.diode.common.Common;
import in.shick.diode.common.Constants;
import in.shick.diode.common.RedditIsFunHttpClientFactory;
import in.shick.diode.common.tasks.HideTask;
import in.shick.diode.common.tasks.SaveTask;
import in.shick.diode.common.util.CollectionUtils;
import in.shick.diode.common.util.StringUtils;
import in.shick.diode.common.util.Util;
import in.shick.diode.login.LoginDialog;
import in.shick.diode.login.LoginTask;
import in.shick.diode.mail.InboxActivity;
import in.shick.diode.mail.PeekEnvelopeTask;
import in.shick.diode.markdown.Markdown;
import in.shick.diode.saved.SavedContent;
import in.shick.diode.saved.SavedDBHandler;
import in.shick.diode.settings.RedditPreferencesPage;
import in.shick.diode.settings.RedditSettings;
import in.shick.diode.things.ThingInfo;
import in.shick.diode.threads.ThreadsListActivity;
import in.shick.diode.threads.ThumbnailOnClickListenerFactory;
import in.shick.diode.user.ProfileActivity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieSyncManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


/**
 * Main Activity class representing a Subreddit, i.e., a ThreadsList.
 *
 * @author TalkLittle
 *
 */
public class CommentsListActivity extends ListActivity
    implements View.OnCreateContextMenuListener {

    public static class ObjectStates {
        DownloadCommentsTask mDownloadCommentsTask = null;
        ArrayList<ThingInfo> mCommentsList = null;
    }

    public ObjectStates mObjectStates = null;

    private static final String TAG = "CommentsListActivity";

    // Group 2: subreddit name. Group 3: thread id36. Group 4: Comment id36.
    private final Pattern COMMENT_PATH_PATTERN = Pattern.compile(Constants.COMMENT_PATH_PATTERN_STRING);
    private final Pattern COMMENT_CONTEXT_PATTERN = Pattern.compile("context=(\\d+)");

    /** Custom list adapter that fits our threads data into the list. */
    CommentsListAdapter mCommentsAdapter = null;

    private final HttpClient mClient = RedditIsFunHttpClientFactory.getGzipHttpClient();

    // Common settings are stored here
    private final RedditSettings mSettings = new RedditSettings();

    private String mSubreddit = null;
    private String mThreadId = null;
    private String mThreadTitle = null;

    // UI State
    private ThingInfo mVoteTargetThing = null;
    private String mContextOPID = null;
    private int mContextCount = 0;
    private String mReportTargetName = null;
    private String mReplyTargetName = null;
    private String mEditTargetBody = null;
    private String mDeleteTargetKind = null;
    private boolean mShouldClearReply = false;

    private String last_search_string;
    private int last_found_position = -1;

    private boolean mCanChord = false;

    private SavedDBHandler sdb = null;
    private ThingInfo saveCommentThing = null;

    // override transition animation available Android 2.0 (SDK Level 5) and above
    private static Method mActivity_overridePendingTransition;

    static {
        initCompatibility();
    }

    private static void initCompatibility() {
        try {
            mActivity_overridePendingTransition = Activity.class.getMethod(
                    "overridePendingTransition", new Class[] { Integer.TYPE, Integer.TYPE } );
            /* success, this is a newer device */
        } catch (NoSuchMethodException nsme) {
            /* failure, must be older device */
        }
    }

    /**
     * Called when the activity starts up. Do activity initialization
     * here, not in a constructor.
     *
     * @see Activity#onCreate
     */
    @SuppressWarnings("unchecked")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sdb = new SavedDBHandler(this);

        CookieSyncManager.createInstance(getApplicationContext());

        mSettings.loadRedditPreferences(this, mClient);

        restoreLastNonConfigurationInstance();
        if(mObjectStates == null) {
            mObjectStates = new ObjectStates();
        }
        else if (mObjectStates.mDownloadCommentsTask != null) {
            if(mObjectStates.mDownloadCommentsTask.getStatus() != Status.FINISHED) {
                mObjectStates.mDownloadCommentsTask.attach(this);
            }
            else {
                mObjectStates.mDownloadCommentsTask = null;
            }
        }

        setRequestedOrientation(mSettings.getRotation());
        setTheme(mSettings.getTheme());
        requestWindowFeature(Window.FEATURE_PROGRESS);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        setContentView(R.layout.comments_list_content);
        registerForContextMenu(getListView());

        if (savedInstanceState != null) {
            if (Constants.LOGGING) Log.d(TAG, "Loading saved instance state");
            mReplyTargetName = savedInstanceState.getString(Constants.REPLY_TARGET_NAME_KEY);
            mReportTargetName = savedInstanceState.getString(Constants.REPORT_TARGET_NAME_KEY);
            mEditTargetBody = savedInstanceState.getString(Constants.EDIT_TARGET_BODY_KEY);
            mDeleteTargetKind = savedInstanceState.getString(Constants.DELETE_TARGET_KIND_KEY);
            mThreadTitle = savedInstanceState.getString(Constants.THREAD_TITLE_KEY);
            mSubreddit = savedInstanceState.getString(Constants.SUBREDDIT_KEY);
            mThreadId = savedInstanceState.getString(Constants.THREAD_ID_KEY);
            mVoteTargetThing = savedInstanceState.getParcelable(Constants.VOTE_TARGET_THING_INFO_KEY);
            mContextOPID = savedInstanceState.getString(Constants.CONTEXT_OP_ID_KEY);
            mContextCount = savedInstanceState.getInt(Constants.CONTEXT_COUNT_KEY);

            if (mThreadTitle != null) {
                setTitle(mThreadTitle + " : " + mSubreddit);
            }

            if (mObjectStates.mCommentsList == null) {
                getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            } else {
                // Orientation change. Use prior instance.
                resetUI(new CommentsListAdapter(this, mObjectStates.mCommentsList));
            }
        }

        // No saved state; use info from Intent.getData()
        else {
            String commentPath;
            String commentQuery;
            String jumpToCommentId = null;
            int jumpToCommentContext = 0;
            // We get the URL through getIntent().getData()
            Uri data = getIntent().getData();
            if (data != null) {
                // Comment path: a URL pointing to a thread or a comment in a thread.
                commentPath = data.getPath();
                commentQuery = data.getQuery();
            } else {
                if (Constants.LOGGING) Log.e(TAG, "Quitting because no subreddit and thread id data was passed into the Intent.");
                finish();
                return;
            }

            if (commentPath != null) {
                if (Constants.LOGGING) Log.d(TAG, "comment path: "+commentPath);

                if (Util.isRedditShortenedUri(data)) {
                    // http://redd.it/abc12
                    mThreadId = commentPath.substring(1);
                } else {
                    // http://www.reddit.com/...
                    Matcher m = COMMENT_PATH_PATTERN.matcher(commentPath);
                    if (m.matches()) {
                        mSubreddit = m.group(1);
                        mThreadId = m.group(2);
                        jumpToCommentId = m.group(3);
                    }
                }
            } else {
                if (Constants.LOGGING) Log.e(TAG, "Quitting because of bad comment path.");
                finish();
                return;
            }

            if (commentQuery != null) {
                Matcher m = COMMENT_CONTEXT_PATTERN.matcher(commentQuery);
                if (m.find()) {
                    jumpToCommentContext = m.group(1) != null ? Integer.valueOf(m.group(1)) : 0;
                }
            }

            // Extras: subreddit, threadTitle, numComments
            // subreddit is not always redundant to Intent.getData(),
            // since URL does not always contain the subreddit. (e.g., self posts)
            Bundle extras = getIntent().getExtras();
            if (extras != null) {
                // subreddit could have already been set from the Intent.getData. don't overwrite with null here!
                String subreddit = extras.getString(Constants.EXTRA_SUBREDDIT);
                if (subreddit != null)
                    mSubreddit = subreddit;
                // mThreadTitle has not been set yet, so no need for null check before setting it
                mThreadTitle = extras.getString(Constants.EXTRA_TITLE);
                if (mThreadTitle != null) {
                    setTitle(mThreadTitle + " : " + mSubreddit);
                }
                // TODO: use extras.getInt(Constants.EXTRA_NUM_COMMENTS) somehow
            }

            setContextOPID(jumpToCommentId);
            setContextCount(jumpToCommentContext);
            getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int previousTheme = mSettings.getTheme();

        mSettings.loadRedditPreferences(this, mClient);

        if (mSettings.getTheme() != previousTheme) {
            relaunchActivity();
        }
        else {
            CookieSyncManager.getInstance().startSync();
            setRequestedOrientation(mSettings.getRotation());

            if (mSettings.isLoggedIn())
                new PeekEnvelopeTask(this, mClient, mSettings.getMailNotificationStyle()).execute();
        }
    }

    private void relaunchActivity() {
        finish();
        startActivity(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieSyncManager.getInstance().stopSync();
        mSettings.saveRedditPreferences(this);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        if(mObjectStates.mDownloadCommentsTask != null) {
            mObjectStates.mDownloadCommentsTask.detach();
        }
        return mObjectStates;
    }

    private void restoreLastNonConfigurationInstance() {
        //mThreadsList = (ArrayList<ThingInfo>) getLastNonConfigurationInstance();
        mObjectStates = (ObjectStates)getLastNonConfigurationInstance();
    }

    private DownloadCommentsTask getNewDownloadCommentsTask() {
        if(mObjectStates.mDownloadCommentsTask == null || mObjectStates.mDownloadCommentsTask.getStatus() == Status.FINISHED) {
            mObjectStates.mDownloadCommentsTask = new DownloadCommentsTask(
                this,
                mSubreddit,
                mThreadId,
                mSettings,
                mClient,
                mContextOPID,
                mContextCount
            );
        } else if (mObjectStates.mDownloadCommentsTask.getStatus() == Status.RUNNING) {
            // Found that if you can click real quickly on the 'Load more comments' button, you can crash the channel.
            // This makes it so that any currently running download task will be allowed to finish before starting
            // another.
            // It would have to be decided if you want to cancel the previous running one and start a new task.
            Toast.makeText(CommentsListActivity.this, R.string.load_in_progress_toast, Toast.LENGTH_SHORT).show();
            return new DoNothingDownloadTask(this,
                                             mSubreddit,
                                             mThreadId,
                                             mSettings,
                                             mClient,
                                             mContextOPID,
                                             mContextCount);
        }
        return mObjectStates.mDownloadCommentsTask;
    }

    public class DoNothingDownloadTask extends DownloadCommentsTask {

        /**
         * Default constructor to do nothing.
         */
        public DoNothingDownloadTask(CommentsListActivity activity, String subreddit, String threadId, RedditSettings settings, HttpClient client, String contextOP, int contextAmount) {
            // Really - do nothing
        }

        @Override
        public void onPreExecute() {
            // Do nothing
        }

        @Override
        public void onPostExecute(Boolean success) {
            // Do nothing
        }

        @Override
        public void onProgressUpdate(Long... progress) {
            // Do nothing
        }

        @Override
        public Boolean doInBackground(Integer... maxComments) {
            return false;
        }
    }

    private boolean isHiddenCommentHeadPosition(int position) {
        return mCommentsAdapter != null && mCommentsAdapter.getItemViewType(position) == CommentsListAdapter.HIDDEN_ITEM_HEAD_VIEW_TYPE;
    }

    private boolean isHiddenCommentDescendantPosition(int position) {
        return mCommentsAdapter != null && mCommentsAdapter.getItem(position).isHiddenCommentDescendant();
    }

    private boolean isLoadMoreCommentsPosition(int position) {
        return mCommentsAdapter != null && mCommentsAdapter.getItemViewType(position) == CommentsListAdapter.MORE_ITEM_VIEW_TYPE;
    }

    final class CommentsListAdapter extends ArrayAdapter<ThingInfo> {
        public static final int OP_ITEM_VIEW_TYPE = 0;
        public static final int COMMENT_ITEM_VIEW_TYPE = 1;
        public static final int MORE_ITEM_VIEW_TYPE = 2;
        public static final int HIDDEN_ITEM_HEAD_VIEW_TYPE = 3;
        public static final int VIEWING_SINGLE_VIEW_TYPE = 4;
        // The number of view types
        public static final int VIEW_TYPE_COUNT = 5;

        public boolean mIsLoading = true;

        private final LayoutInflater mInflater;
        private final int mFrequentSeparatorPos = ListView.INVALID_POSITION;

        public CommentsListAdapter(Context context, List<ThingInfo> objects) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return OP_ITEM_VIEW_TYPE;
            }
            if (position == mFrequentSeparatorPos) {
                // We don't want the separator view to be recycled.
                return IGNORE_ITEM_VIEW_TYPE;
            }

            ThingInfo item = getItem(position);
            if (item.isHiddenCommentDescendant()) {
                return IGNORE_ITEM_VIEW_TYPE;
            } else if (item.isHiddenCommentHead()) {
                return HIDDEN_ITEM_HEAD_VIEW_TYPE;
            } else if (item.isLoadMoreCommentsPlaceholder()) {
                return MORE_ITEM_VIEW_TYPE;
            } else if (item.isContext()) {
                return VIEWING_SINGLE_VIEW_TYPE;
            }

            return COMMENT_ITEM_VIEW_TYPE;
        }

        @Override
        public int getViewTypeCount() {
            return VIEW_TYPE_COUNT;
        }

        @Override
        public boolean isEmpty() {
            if (mIsLoading)
                return false;
            return super.isEmpty();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;

            ThingInfo item = this.getItem(position);

            try {
                if (position == 0) {
                    // The OP
                    if (view == null) {
                        view = mInflater.inflate(R.layout.threads_list_item, null);
                    }
                    ThreadsListActivity.fillThreadsListItemView(
                        position, view, item, CommentsListActivity.this, mClient, mSettings, mThumbnailOnClickListenerFactory
                    );
                    if (item.isIs_self()) {
                        View thumbnailContainer = view.findViewById(R.id.thumbnail_view);
                        if (thumbnailContainer != null)
                            thumbnailContainer.setVisibility(View.GONE);
                    }

                    // In addition to stuff from ThreadsListActivity,
                    // we want to show selftext in CommentsListActivity.

                    TextView submissionStuffView = (TextView) view.findViewById(R.id.submissionTime_submitter);
                    TextView selftextView = (TextView) view.findViewById(R.id.selftext);

                    SpannableString distSS = null;
                    if (Constants.DISTINGUISHED_MODERATOR.equalsIgnoreCase(item.getDistinguished())) {
                        distSS = new SpannableString(item.getAuthor() + " [M]");
                        distSS.setSpan(Util.getModeratorSpan(getApplicationContext()), 0, distSS.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (Constants.DISTINGUISHED_ADMIN.equalsIgnoreCase(item.getDistinguished())) {
                        distSS = new SpannableString(item.getAuthor() + " [A]");
                        distSS.setSpan(Util.getAdminSpan(getApplicationContext()), 0, distSS.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        distSS = new SpannableString(item.getAuthor());
                    }
                    SpannableStringBuilder ssb = new SpannableStringBuilder(String.format(getResources().getString(R.string.thread_time_submitter),
                            Util.getTimeAgo(item.getCreated_utc(), getResources())));
                    ssb.append(" ").append(distSS);
                    submissionStuffView.setVisibility(View.VISIBLE);
                    submissionStuffView.setText(ssb);

                    if (!StringUtils.isEmpty(item.getSpannedSelftext())) {
                        selftextView.setVisibility(View.VISIBLE);
                        selftextView.setText(item.getSpannedSelftext());
                    } else {
                        selftextView.setVisibility(View.GONE);
                    }

                } else if (isHiddenCommentDescendantPosition(position)) {
                    if (view == null) {
                        // Doesn't matter which view we inflate since it's gonna be invisible
                        view = mInflater.inflate(R.layout.zero_size_layout, null);
                        loadAndStoreViewHolder(view);
                    }
                } else if (isHiddenCommentHeadPosition(position)) {
                    if (view == null) {
                        view = mInflater.inflate(R.layout.comments_list_item_hidden, null);
                        loadAndStoreViewHolder(view);
                    }
                    ViewHolder vh = (ViewHolder)view.getTag();

                    try {
                        vh.votesView.setText(Util.showNumPoints(item.getUps() - item.getDowns()));
                    } catch (NumberFormatException e) {
                        // This happens because "ups" comes after the potentially long "replies" object,
                        // so the ListView might try to display the View before "ups" in JSON has been parsed.
                        if (Constants.LOGGING) Log.e(TAG, "getView, hidden comment heads", e);
                    }
                    if (getOpThingInfo() != null && item.getAuthor().equalsIgnoreCase(getOpThingInfo().getAuthor())) {
                        vh.submitterView.setText(item.getAuthor() + " [S]");
                    } else {
                        vh.submitterView.setText(item.getAuthor());
                    }
                    vh.submissionTimeView.setText(Util.getTimeAgo(item.getCreated_utc(), getResources()));

                    setCommentIndent(view, item.getIndent(), mSettings);

                } else if (isLoadMoreCommentsPosition(position)) {
                    // "load more comments"
                    if (view == null) {
                        view = mInflater.inflate(R.layout.more_comments_view, null);
                        loadAndStoreViewHolder(view);
                    }

                    setCommentIndent(view, item.getIndent(), mSettings);

                } else if (item.isContext()) {
                    if (view == null) {
                        view = mInflater.inflate(R.layout.viewing_single_comment_list_item, null);
                        loadAndStoreViewHolder(view);
                    }
                    setContextOPID(item.getId());
                } else {  // Regular comment
                    // Here view may be passed in for re-use, or we make a new one.
                    if (view == null) {
                        view = mInflater.inflate(R.layout.comments_list_item, null);
                        loadAndStoreViewHolder(view);
                    } else {
                        view = convertView;
                    }

                    // Sometimes (when in touch mode) the "selection" highlight disappears.
                    // So we make our own persistent highlight. This background color must
                    // be set explicitly on every element, however, or the "cached" list
                    // item views will show up with the color.
                    if (position == last_found_position)
                        view.setBackgroundResource(R.color.translucent_yellow);
                    else
                        view.setBackgroundColor(Color.TRANSPARENT);

                    fillCommentsListItemView(view, item, mSettings);
                }
            } catch (NullPointerException e) {
                if (Constants.LOGGING) Log.w(TAG, "NPE in getView()", e);
                // Probably means that the List is still being built, and OP probably got put in wrong position
                if (view == null) {
                    if (position == 0)
                        view = mInflater.inflate(R.layout.threads_list_item, null);
                    else
                        view = mInflater.inflate(R.layout.comments_list_item, null);
                }
            }
            return view;
        }
    } // End of CommentsListAdapter

    private ThingInfo getOpThingInfo() {
        if (!CollectionUtils.isEmpty(mObjectStates.mCommentsList))
            return mObjectStates.mCommentsList.get(0);
        return null;
    }

    public void setThreadTitle(String threadTitle) {
        this.mThreadTitle = threadTitle;
    }

    public void setShouldClearReply(boolean shouldClearReply) {
        this.mShouldClearReply = shouldClearReply;
    }

    private static void setCommentIndent(View commentListItemView, int indentLevel, RedditSettings settings) {
        View[] indentViews = ((ViewHolder)commentListItemView.getTag()).indentViews;
        for (int i = 0; i < indentLevel && i < indentViews.length; i++) {
            if (settings.isShowCommentGuideLines()) {
                indentViews[i].setVisibility(View.VISIBLE);
                if (Util.isLightTheme(settings.getTheme())) {
                    indentViews[i].setBackgroundResource(R.color.light_light_gray);
                } else {
                    indentViews[i].setBackgroundResource(R.color.dark_gray);
                }
            } else {
                indentViews[i].setVisibility(View.INVISIBLE);
            }
        }
        for (int i = indentLevel; i < indentViews.length; i++) {
            indentViews[i].setVisibility(View.GONE);
        }
    }


    /**
     * Called when user clicks an item in the list. Starts an activity to
     * open the url for that item.
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        ThingInfo item = mCommentsAdapter.getItem(position);

        if (isHiddenCommentHeadPosition(position)) {
            showComment(position);
            return;
        }
        // Mark the OP post/regular comment as selected
        if (!item.isContext()) {
            mVoteTargetThing = item;
            mReplyTargetName = mVoteTargetThing.getName();
        }

        if (isLoadMoreCommentsPosition(position)) {
            // Use this constructor to tell it to load more comments inline
            getNewDownloadCommentsTask().prepareLoadMoreComments(item.getId(), position, item.getIndent())
            .execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        } else {
            // Clicking the comment-context warning takes you to the whole comments thread.
            if (item.isContext()) {
                resetContextInfo();
                getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            } else {
                // Lazy-load the URL list to make the list-loading more 'snappy'.
                if (mVoteTargetThing.getUrls() != null && mVoteTargetThing.getUrls().isEmpty()) {
                    Markdown.getURLs(mVoteTargetThing.getBody(), mVoteTargetThing.getUrls());
                }
                if (!"[deleted]".equals(item.getAuthor())) {
                    showDialog(Constants.DIALOG_COMMENT_CLICK);
                }
            }
        }
    }

    /**
     * Resets the output UI list contents, retains session state.
     * @param commentsAdapter A new CommentsListAdapter to use. Pass in null to create a new empty one.
     */
    void resetUI(CommentsListAdapter commentsAdapter) {
        if( !(mCommentsAdapter != commentsAdapter && mObjectStates.mDownloadCommentsTask != null && mObjectStates.mDownloadCommentsTask.getStatus() != Status.FINISHED) ) {
            findViewById(R.id.loading_light).setVisibility(View.GONE);
            findViewById(R.id.loading_dark).setVisibility(View.GONE);
        } else {
            if (Util.isLightTheme(mSettings.getTheme()))
                findViewById(R.id.loading_dark).setVisibility(View.GONE);
            else
                findViewById(R.id.loading_light).setVisibility(View.GONE);
        }
        if (commentsAdapter == null) {
            // Reset the list to be empty.
            mObjectStates.mCommentsList = new ArrayList<ThingInfo>();
            mCommentsAdapter = new CommentsListAdapter(this, mObjectStates.mCommentsList);
            setListAdapter(mCommentsAdapter);
        } else if (mCommentsAdapter != commentsAdapter) {
            mCommentsAdapter = commentsAdapter;
            setListAdapter(commentsAdapter);
        }

        mCommentsAdapter.mIsLoading = false;
        mCommentsAdapter.notifyDataSetChanged();  // Just in case
        getListView().setDivider(null);
        Common.updateListDrawables(this, mSettings.getTheme());
    }

    void enableLoadingScreen() {
        if (Util.isLightTheme(mSettings.getTheme())) {
            findViewById(R.id.loading_light).setVisibility(View.VISIBLE);
            findViewById(R.id.loading_dark).setVisibility(View.GONE);
        } else {
            findViewById(R.id.loading_light).setVisibility(View.GONE);
            findViewById(R.id.loading_dark).setVisibility(View.VISIBLE);
        }
        if (mCommentsAdapter != null)
            mCommentsAdapter.mIsLoading = true;
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_START);
    }

    private void resetContextInfo() {
        mContextOPID = null;
        mContextCount = 0;
    }

    private void setContextOPID(String contextOPID) {
        mContextOPID = contextOPID;
    }

    private void setContextCount(int contextCount) {
        mContextCount = contextCount;
    }

    private class MyLoginTask extends LoginTask {
        public MyLoginTask(String username, String password) {
            super(username, password, mSettings, mClient, getApplicationContext());
        }

        @Override
        protected void onPreExecute() {
            showDialog(Constants.DIALOG_LOGGING_IN);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            removeDialog(Constants.DIALOG_LOGGING_IN);
            if (success) {
                Toast.makeText(CommentsListActivity.this, "Logged in as "+mUsername, Toast.LENGTH_SHORT).show();
                // Check mail
                new PeekEnvelopeTask(CommentsListActivity.this, mClient, mSettings.getMailNotificationStyle()).execute();
                // Refresh the comments list
                getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            } else {
                Common.showErrorToast(mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
            }
        }
    }




    private class CommentReplyTask extends AsyncTask<String, Void, String> {
        private final String _mParentThingId;
        String _mUserError = "Error submitting reply. Please try again.";

        CommentReplyTask(String parentThingId) {
            _mParentThingId = parentThingId;
        }

        @Override
        public String doInBackground(String... text) {
            HttpEntity entity = null;

            if (!mSettings.isLoggedIn()) {
                Common.showErrorToast("You must be logged in to reply.", Toast.LENGTH_LONG, CommentsListActivity.this);
                _mUserError = "Not logged in";
                return null;
            }
            // Update the modhash if necessary
            if (mSettings.getModhash() == null) {
                String modhash = Common.doUpdateModhash(mClient);
                if (modhash == null) {
                    // doUpdateModhash should have given an error about credentials
                    Common.doLogout(mSettings, mClient, getApplicationContext());
                    if (Constants.LOGGING) Log.e(TAG, "Reply failed because doUpdateModhash() failed");
                    return null;
                }
                mSettings.setModhash(modhash);
            }

            try {
                // Construct data
                List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                nvps.add(new BasicNameValuePair("thing_id", _mParentThingId));
                nvps.add(new BasicNameValuePair("text", text[0]));
                nvps.add(new BasicNameValuePair("r", mSubreddit));
                nvps.add(new BasicNameValuePair("uh", mSettings.getModhash()));
                // Votehash is currently unused by reddit
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));

                HttpPost httppost = new HttpPost(Constants.REDDIT_BASE_URL + "/api/comment");
                httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));

                HttpParams params = httppost.getParams();
                HttpConnectionParams.setConnectionTimeout(params, 40000);
                HttpConnectionParams.setSoTimeout(params, 40000);

                if (Constants.LOGGING) Log.d(TAG, nvps.toString());

                // Perform the HTTP POST request
                HttpResponse response = mClient.execute(httppost);
                entity = response.getEntity();

                // Getting here means success. Create a new CommentInfo.
                return Common.checkIDResponse(response, entity);

            } catch (Exception e) {
                if (Constants.LOGGING) Log.e(TAG, "CommentReplyTask", e);
                _mUserError = e.getMessage();
            } finally {
                if (entity != null) {
                    try {
                        entity.consumeContent();
                    } catch (Exception e2) {
                        if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
                    }
                }
            }
            return null;
        }

        @Override
        public void onPreExecute() {
            showDialog(Constants.DIALOG_REPLYING);
        }

        @Override
        public void onPostExecute(String newId) {
            removeDialog(Constants.DIALOG_REPLYING);
            if (newId == null) {
                Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
            } else {
                // Refresh
                CacheInfo.invalidateCachedThread(getApplicationContext());
                getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            }
        }
    }

    private class EditTask extends AsyncTask<String, Void, String> {
        private final String _mThingId;
        String _mUserError = "Error submitting edit. Please try again.";

        EditTask(String thingId) {
            _mThingId = thingId;
        }

        @Override
        public String doInBackground(String... text) {
            HttpEntity entity = null;

            if (!mSettings.isLoggedIn()) {
                _mUserError = "You must be logged in to edit.";
                return null;
            }
            // Update the modhash if necessary
            if (mSettings.getModhash() == null) {
                String modhash = Common.doUpdateModhash(mClient);
                if (modhash == null) {
                    // doUpdateModhash should have given an error about credentials
                    Common.doLogout(mSettings, mClient, getApplicationContext());
                    if (Constants.LOGGING) Log.e(TAG, "Reply failed because doUpdateModhash() failed");
                    return null;
                }
                mSettings.setModhash(modhash);
            }

            try {
                // Construct data
                List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                nvps.add(new BasicNameValuePair("thing_id", _mThingId.toString()));
                nvps.add(new BasicNameValuePair("text", text[0].toString()));
                nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
                nvps.add(new BasicNameValuePair("uh", mSettings.getModhash().toString()));

                HttpPost httppost = new HttpPost(Constants.REDDIT_BASE_URL + "/api/editusertext");
                httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));

                HttpParams params = httppost.getParams();
                HttpConnectionParams.setConnectionTimeout(params, 40000);
                HttpConnectionParams.setSoTimeout(params, 40000);

                if (Constants.LOGGING) Log.d(TAG, nvps.toString());

                // Perform the HTTP POST request
                HttpResponse response = mClient.execute(httppost);
                entity = response.getEntity();

                return Common.checkIDResponse(response, entity);

            } catch (Exception e) {
                if (Constants.LOGGING) Log.e(TAG, "EditTask", e);
                _mUserError = e.getMessage();
            } finally {
                if (entity != null) {
                    try {
                        entity.consumeContent();
                    } catch (Exception e2) {
                        if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
                    }
                }
            }
            return null;
        }

        @Override
        public void onPreExecute() {
            showDialog(Constants.DIALOG_EDITING);
        }

        @Override
        public void onPostExecute(String newId) {
            removeDialog(Constants.DIALOG_EDITING);
            if (newId == null) {
                Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
            } else {
                // Refresh
                CacheInfo.invalidateCachedThread(getApplicationContext());
                getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            }
        }
    }

    private class DeleteTask extends AsyncTask<String, Void, Boolean> {
        private String _mUserError = "Error deleting. Please try again.";
        private final String _mKind;

        public DeleteTask(String kind) {
            _mKind = kind;
        }

        @Override
        public Boolean doInBackground(String... thingFullname) {
//    		POSTDATA=id=t1_c0cxa7l&executed=deleted&r=test&uh=f7jb1yjwfqd4ffed8356eb63fcfbeeadad142f57c56e9cbd9e

            HttpEntity entity = null;

            if (!mSettings.isLoggedIn()) {
                _mUserError = "You must be logged in to delete.";
                return false;
            }
            // Update the modhash if necessary
            if (mSettings.getModhash() == null) {
                String modhash = Common.doUpdateModhash(mClient);
                if (modhash == null) {
                    // doUpdateModhash should have given an error about credentials
                    Common.doLogout(mSettings, mClient, getApplicationContext());
                    if (Constants.LOGGING) Log.e(TAG, "Reply failed because doUpdateModhash() failed");
                    return false;
                }
                mSettings.setModhash(modhash);
            }

            try {
                // Construct data
                List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                nvps.add(new BasicNameValuePair("id", thingFullname[0].toString()));
                nvps.add(new BasicNameValuePair("executed", "deleted"));
                nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
                nvps.add(new BasicNameValuePair("uh", mSettings.getModhash().toString()));

                HttpPost httppost = new HttpPost(Constants.REDDIT_BASE_URL + "/api/del");
                httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));

                HttpParams params = httppost.getParams();
                HttpConnectionParams.setConnectionTimeout(params, 40000);
                HttpConnectionParams.setSoTimeout(params, 40000);

                if (Constants.LOGGING) Log.d(TAG, nvps.toString());

                // Perform the HTTP POST request
                HttpResponse response = mClient.execute(httppost);
                entity = response.getEntity();

                String error = Common.checkResponseErrors(response, entity);
                if (error != null)
                    throw new Exception(error);

                // Success
                return true;

            } catch (Exception e) {
                if (Constants.LOGGING) Log.e(TAG, "DeleteTask", e);
                _mUserError = e.getMessage();
            } finally {
                if (entity != null) {
                    try {
                        entity.consumeContent();
                    } catch (Exception e2) {
                        if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
                    }
                }
            }
            return false;
        }

        @Override
        public void onPreExecute() {
            showDialog(Constants.DIALOG_DELETING);
        }

        @Override
        public void onPostExecute(Boolean success) {
            removeDialog(Constants.DIALOG_DELETING);
            if (success) {
                CacheInfo.invalidateCachedThread(getApplicationContext());
                if (Constants.THREAD_KIND.equals(_mKind)) {
                    Toast.makeText(CommentsListActivity.this, "Deleted thread.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                } else {
                    Toast.makeText(CommentsListActivity.this, "Deleted comment.", Toast.LENGTH_SHORT).show();
                    getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
                }
            } else {
                Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
            }
        }
    }

    private class VoteTask extends AsyncTask<Void, Void, Boolean> {

        private static final String TAG = "VoteWorker";

        private final String _mThingFullname;
        private final int _mDirection;
        private String _mUserError = "Error voting.";
        private final ThingInfo _mTargetThingInfo;

        // Save the previous arrow and score in case we need to revert
        private int _mPreviousUps, _mPreviousDowns;
        private Boolean _mPreviousLikes;

        VoteTask(String thingFullname, int direction) {
            _mThingFullname = thingFullname;
            _mDirection = direction;
            // Copy these because they can change while voting thread is running
            _mTargetThingInfo = mVoteTargetThing;
        }

        @Override
        public Boolean doInBackground(Void... v) {
            HttpEntity entity = null;

            if (!mSettings.isLoggedIn()) {
                _mUserError = "You must be logged in to vote.";
                return false;
            }

            // Update the modhash if necessary
            if (mSettings.getModhash() == null) {
                String modhash = Common.doUpdateModhash(mClient);
                if (modhash == null) {
                    // doUpdateModhash should have given an error about credentials
                    Common.doLogout(mSettings, mClient, getApplicationContext());
                    if (Constants.LOGGING) Log.e(TAG, "Vote failed because doUpdateModhash() failed");
                    return false;
                }
                mSettings.setModhash(modhash);
            }

            try {
                // Construct data
                List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                nvps.add(new BasicNameValuePair("id", _mThingFullname.toString()));
                nvps.add(new BasicNameValuePair("dir", String.valueOf(_mDirection)));
                nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
                nvps.add(new BasicNameValuePair("uh", mSettings.getModhash().toString()));
                // Votehash is currently unused by reddit
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));

                HttpPost httppost = new HttpPost(Constants.REDDIT_BASE_URL + "/api/vote");
                httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));

                if (Constants.LOGGING) Log.d(TAG, nvps.toString());

                // Perform the HTTP POST request
                HttpResponse response = mClient.execute(httppost);
                entity = response.getEntity();

                String error = Common.checkResponseErrors(response, entity);
                if (error != null)
                    throw new Exception(error);

                return true;
            } catch (Exception e) {
                if (Constants.LOGGING) Log.e(TAG, "VoteTask", e);
                _mUserError = e.getMessage();
            } finally {
                if (entity != null) {
                    try {
                        entity.consumeContent();
                    } catch (Exception e2) {
                        if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
                    }
                }
            }
            return false;
        }

        public void onPreExecute() {
            if (!mSettings.isLoggedIn()) {
                Common.showErrorToast("You must be logged in to vote.", Toast.LENGTH_LONG, CommentsListActivity.this);
                cancel(true);
                return;
            }
            if (_mDirection < -1 || _mDirection > 1) {
                if (Constants.LOGGING) Log.e(TAG, "WTF: _mDirection = " + _mDirection);
                throw new RuntimeException("How the hell did you vote something besides -1, 0, or 1?");
            }

            int newUps, newDowns;
            Boolean newLikes;
            _mPreviousUps = Integer.valueOf(_mTargetThingInfo.getUps());
            _mPreviousDowns = Integer.valueOf(_mTargetThingInfo.getDowns());
            newUps = _mPreviousUps;
            newDowns = _mPreviousDowns;
            _mPreviousLikes = _mTargetThingInfo.getLikes();

            if (_mPreviousLikes == null) {
                if (_mDirection == 1) {
                    newUps = _mPreviousUps + 1;
                    newLikes = true;
                } else if (_mDirection == -1) {
                    newDowns = _mPreviousDowns + 1;
                    newLikes = false;
                } else {
                    cancel(true);
                    return;
                }
            } else if (_mPreviousLikes == true) {
                if (_mDirection == 0) {
                    newUps = _mPreviousUps - 1;
                    newLikes = null;
                } else if (_mDirection == -1) {
                    newUps = _mPreviousUps - 1;
                    newDowns = _mPreviousDowns + 1;
                    newLikes = false;
                } else {
                    cancel(true);
                    return;
                }
            } else {
                if (_mDirection == 1) {
                    newUps = _mPreviousUps + 1;
                    newDowns = _mPreviousDowns - 1;
                    newLikes = true;
                } else if (_mDirection == 0) {
                    newDowns = _mPreviousDowns - 1;
                    newLikes = null;
                } else {
                    cancel(true);
                    return;
                }
            }

            _mTargetThingInfo.setLikes(newLikes);
            _mTargetThingInfo.setUps(newUps);
            _mTargetThingInfo.setDowns(newDowns);
            _mTargetThingInfo.setScore(newUps - newDowns);
            mCommentsAdapter.notifyDataSetChanged();
        }

        public void onPostExecute(Boolean success) {
            if (success) {
                CacheInfo.invalidateCachedThread(getApplicationContext());
            } else {
                // Vote failed. Undo the arrow and score.
                _mTargetThingInfo.setLikes(_mPreviousLikes);
                _mTargetThingInfo.setUps(_mPreviousUps);
                _mTargetThingInfo.setDowns(_mPreviousDowns);
                _mTargetThingInfo.setScore(_mPreviousUps - _mPreviousDowns);
                mCommentsAdapter.notifyDataSetChanged();

                Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
            }
        }
    }



    private class ReportTask extends AsyncTask<Void, Void, Boolean> {

        private static final String TAG = "ReportTask";

        private String _mUserError = "Error reporting.";
        private final String _mFullId;

        ReportTask(String fullname) {
            this._mFullId = fullname;
        }

        @Override
        public Boolean doInBackground(Void... v) {
            HttpEntity entity = null;

            if (!mSettings.isLoggedIn()) {
                _mUserError = "You must be logged in to report something.";
                return false;
            }

            // Update the modhash if necessary
            if (mSettings.getModhash() == null) {
                String modhash = Common.doUpdateModhash(mClient);
                if (modhash == null) {
                    // doUpdateModhash should have given an error about credentials
                    Common.doLogout(mSettings, mClient, getApplicationContext());
                    if (Constants.LOGGING) Log.e(TAG, "Report failed because doUpdateModhash() failed");
                    return false;
                }
                mSettings.setModhash(modhash);
            }

            try {
                // Construct data
                List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                nvps.add(new BasicNameValuePair("id", _mFullId));
                nvps.add(new BasicNameValuePair("executed", "reported"));
                nvps.add(new BasicNameValuePair("r", mSubreddit.toString()));
                nvps.add(new BasicNameValuePair("uh", mSettings.getModhash().toString()));
                // Votehash is currently unused by reddit
//    				nvps.add(new BasicNameValuePair("vh", "0d4ab0ffd56ad0f66841c15609e9a45aeec6b015"));

                HttpPost httppost = new HttpPost(Constants.REDDIT_BASE_URL + "/api/report");
                httppost.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));

                if (Constants.LOGGING) Log.d(TAG, nvps.toString());

                // Perform the HTTP POST request
                HttpResponse response = mClient.execute(httppost);
                entity = response.getEntity();

                String error = Common.checkResponseErrors(response, entity);
                if (error != null)
                    throw new Exception(error);

                // Success
                return true;

            } catch (Exception e) {
                if (Constants.LOGGING) Log.e(TAG, "ReportTask", e);
            } finally {
                if (entity != null) {
                    try {
                        entity.consumeContent();
                    } catch (Exception e2) {
                        if (Constants.LOGGING) Log.e(TAG, "entity.consumeContent()", e2);
                    }
                }
            }
            return false;
        }

        public void onPreExecute() {
            if (!mSettings.isLoggedIn()) {
                Common.showErrorToast("You must be logged in to report this.", Toast.LENGTH_LONG, CommentsListActivity.this);
                cancel(true);
                return;
            }
        }

        public void onPostExecute(Boolean success) {
            if (success) {
                Toast.makeText(CommentsListActivity.this, "Reported.", Toast.LENGTH_SHORT);
            } else {
                Common.showErrorToast(_mUserError, Toast.LENGTH_LONG, CommentsListActivity.this);
            }
        }
    }

    /**
     * Populates the menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.comments, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // This happens when the user begins to hold down the menu key, so
        // allow them to chord to get a shortcut.
        mCanChord = true;

        super.onPrepareOptionsMenu(menu);

        MenuItem src, dest;

        menu.findItem(R.id.find_next_menu_id).setVisible(last_search_string != null && last_search_string.length() > 0);

        // Login/Logout
        if (mSettings.isLoggedIn()) {
            menu.findItem(R.id.login_menu_id).setVisible(false);
            menu.findItem(R.id.inbox_menu_id).setVisible(true);
            menu.findItem(R.id.user_profile_menu_id).setVisible(true);
            menu.findItem(R.id.user_profile_menu_id).setTitle(
                String.format(getResources().getString(R.string.user_profile), mSettings.getUsername())
            );
            menu.findItem(R.id.logout_menu_id).setVisible(true);
            menu.findItem(R.id.logout_menu_id).setTitle(
                String.format(getResources().getString(R.string.logout), mSettings.getUsername())
            );
            menu.findItem(R.id.saved_comments_menu_id).setVisible(true);
        } else {
            menu.findItem(R.id.login_menu_id).setVisible(true);
            menu.findItem(R.id.inbox_menu_id).setVisible(false);
            menu.findItem(R.id.user_profile_menu_id).setVisible(false);
            menu.findItem(R.id.logout_menu_id).setVisible(false);
            menu.findItem(R.id.saved_comments_menu_id).setVisible(false);
        }

        // Edit and delete
        if (getOpThingInfo() != null) {
            if (mSettings.getUsername() != null && mSettings.getUsername().equalsIgnoreCase(getOpThingInfo().getAuthor())) {
                if (getOpThingInfo().isIs_self())
                    menu.findItem(R.id.op_edit_menu_id).setVisible(true);
                else
                    menu.findItem(R.id.op_edit_menu_id).setVisible(false);
                menu.findItem(R.id.op_delete_menu_id).setVisible(true);
            } else {
                menu.findItem(R.id.op_edit_menu_id).setVisible(false);
                menu.findItem(R.id.op_delete_menu_id).setVisible(false);
            }
        }

        // Theme: Light/Dark
        src = Util.isLightTheme(mSettings.getTheme()) ?
              menu.findItem(R.id.dark_menu_id) :
              menu.findItem(R.id.light_menu_id);
        dest = menu.findItem(R.id.light_dark_menu_id);
        dest.setTitle(src.getTitle());

        // Sort
        if (Constants.CommentsSort.SORT_BY_BEST_URL.equals(mSettings.getCommentsSortByUrl()))
            src = menu.findItem(R.id.sort_by_best_menu_id);
        else if (Constants.CommentsSort.SORT_BY_HOT_URL.equals(mSettings.getCommentsSortByUrl()))
            src = menu.findItem(R.id.sort_by_hot_menu_id);
        else if (Constants.CommentsSort.SORT_BY_NEW_URL.equals(mSettings.getCommentsSortByUrl()))
            src = menu.findItem(R.id.sort_by_new_menu_id);
        else if (Constants.CommentsSort.SORT_BY_CONTROVERSIAL_URL.equals(mSettings.getCommentsSortByUrl()))
            src = menu.findItem(R.id.sort_by_controversial_menu_id);
        else if (Constants.CommentsSort.SORT_BY_TOP_URL.equals(mSettings.getCommentsSortByUrl()))
            src = menu.findItem(R.id.sort_by_top_menu_id);
        else if (Constants.CommentsSort.SORT_BY_OLD_URL.equals(mSettings.getCommentsSortByUrl()))
            src = menu.findItem(R.id.sort_by_old_menu_id);
        dest = menu.findItem(R.id.sort_by_menu_id);
        dest.setTitle(src.getTitle());

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!mCanChord) {
            // The user has already fired a shortcut with this hold down of the
            // menu key.
            return false;
        }

        switch (item.getItemId()) {
        case R.id.op_menu_id:
            if (getOpThingInfo() == null)
                break;
            mVoteTargetThing = getOpThingInfo();
            mReplyTargetName = getOpThingInfo().getName();
            showDialog(Constants.DIALOG_COMMENT_CLICK);
            break;
        case R.id.op_subreddit_menu_id:
            Intent intent = new Intent(getApplicationContext(), ThreadsListActivity.class);
            intent.setData(Util.createSubredditUri(mSubreddit));
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            Util.overridePendingTransition(mActivity_overridePendingTransition, this,
                                           android.R.anim.slide_in_left, android.R.anim.slide_out_right);
            break;
        case R.id.login_menu_id:
            showDialog(Constants.DIALOG_LOGIN);
            break;
        case R.id.logout_menu_id:
            Common.doLogout(mSettings, mClient, getApplicationContext());
            Toast.makeText(this, "You have been logged out.", Toast.LENGTH_SHORT).show();
            getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            break;
        case R.id.find_next_menu_id:
            if (last_search_string != null && last_search_string.length() > 0)
                findCommentText(last_search_string, true, true);
            break;
        case R.id.find_base_id:
            // This case is needed because the "default" case throws
            // an error, otherwise precluding anonymous "parent" menu items
            break;
        case R.id.find_menu_id:
            showDialog(Constants.DIALOG_FIND);
            break;
        case R.id.refresh_menu_id:
            CacheInfo.invalidateCachedThread(getApplicationContext());
            DownloadCommentsTask downloadCommentsTask = getNewDownloadCommentsTask();
            if(downloadCommentsTask.getStatus() != AsyncTask.Status.RUNNING)
                downloadCommentsTask.execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            break;
        case R.id.sort_by_menu_id:
            showDialog(Constants.DIALOG_SORT_BY);
            break;
        case R.id.open_browser_menu_id:
            String url = new StringBuilder(Constants.REDDIT_BASE_URL + "/r/")
            .append(mSubreddit).append("/comments/").append(mThreadId).toString();
            Common.launchBrowser(this, url, url, false, true, true, false);
            break;
        case R.id.op_delete_menu_id:
            mReplyTargetName = getOpThingInfo().getName();
            mDeleteTargetKind = Constants.THREAD_KIND;
            showDialog(Constants.DIALOG_DELETE);
            break;
        case R.id.op_edit_menu_id:
            mReplyTargetName = getOpThingInfo().getName();
            mEditTargetBody = getOpThingInfo().getSelftext();
            showDialog(Constants.DIALOG_EDIT);
            break;
        case R.id.light_dark_menu_id:
            mSettings.setTheme(Util.getInvertedTheme(mSettings.getTheme()));
            relaunchActivity();
            break;
        case R.id.inbox_menu_id:
            Intent inboxIntent = new Intent(getApplicationContext(), InboxActivity.class);
            startActivity(inboxIntent);
            break;
        case R.id.user_profile_menu_id:
            Intent profileIntent = new Intent(getApplicationContext(), ProfileActivity.class);
            startActivity(profileIntent);
            break;
        case R.id.preferences_menu_id:
            Intent prefsIntent = new Intent(getApplicationContext(), RedditPreferencesPage.class);
            startActivity(prefsIntent);
            break;
        case R.id.saved_comments_menu_id:
            Intent toSC = new Intent(getApplicationContext(), SavedCommentsActivity.class);
            startActivity(toSC);
            break;
        case android.R.id.home:
            Common.goHome(this);
            break;
        default:
            throw new IllegalArgumentException("Unexpected action value "+item.getItemId());
        }

        return true;
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        int rowId = (int) info.id;

        ThingInfo item = mCommentsAdapter.getItem(rowId);

        if (rowId == 0) {
            menu.add(0, Constants.SHARE_CONTEXT_ITEM, Menu.NONE, "Share");
            menu.add(0, Constants.COPY_CONTEXT_ITEM, Menu.NONE, R.string.copy);

            if(getOpThingInfo().isSaved()) {
                menu.add(0, Constants.UNSAVE_CONTEXT_ITEM, Menu.NONE, "Unsave");
            } else {
                menu.add(0, Constants.SAVE_CONTEXT_ITEM, Menu.NONE, "Save");
            }
            if(getOpThingInfo().isHidden()) {
                menu.add(0, Constants.UNHIDE_CONTEXT_ITEM, Menu.NONE, "Unhide");
            } else {
                menu.add(0, Constants.HIDE_CONTEXT_ITEM, Menu.NONE, "Hide");
            }

            // Make sure the user isn't '[deleted]'
            if (!item.isDeletedUser()) {
                menu.add(0, Constants.DIALOG_VIEW_PROFILE, Menu.NONE,
                         String.format(getResources().getString(R.string.user_profile), item.getAuthor()));
            }

        } else if (isLoadMoreCommentsPosition(rowId)) {
            menu.add(0, Constants.DIALOG_FOCUS_PARENT, Menu.NONE, R.string.focus_parent_comment);
        } else if (isHiddenCommentHeadPosition(rowId)) {
            menu.add(0, Constants.DIALOG_SHOW_COMMENT, Menu.NONE, R.string.show_comment);
            menu.add(0, Constants.DIALOG_FOCUS_PARENT, Menu.NONE, R.string.focus_parent_comment);
        } else if (item.isContext()) {
            // If the top-level context item has a parent comment, then there's more context to be viewed.
            if (item.getParent_id() != null && item.isParentAComment()) {
                menu.add(0, Constants.DIALOG_FULL_CONTEXT, Menu.NONE, R.string.view_full_context);
            }
            menu.add(0, Constants.DIALOG_THREAD_CLICK, Menu.NONE, R.string.goto_thread);
        } else {
            if (mSettings.getUsername() != null && mSettings.getUsername().equalsIgnoreCase(item.getAuthor())) {
                menu.add(0, Constants.DIALOG_EDIT, Menu.NONE, "Edit");
                menu.add(0, Constants.DIALOG_DELETE, Menu.NONE, "Delete");
            }
            menu.add(0, Constants.DIALOG_HIDE_COMMENT, Menu.NONE, R.string.hide_comment);
//    		if (mSettings.isLoggedIn())
//    			menu.add(0, Constants.DIALOG_REPORT, Menu.NONE, "Report comment");
            menu.add(0, Constants.DIALOG_FOCUS_PARENT, Menu.NONE, R.string.focus_parent_comment);
            if (item.isParentAComment()) {
                menu.add(0, Constants.DIALOG_VIEW_CONTEXT, Menu.NONE, R.string.view_context);
            }
            if (mSettings.isLoggedIn())
            {
                saveCommentThing = item;
                if (sdb.containsComment(mSettings.getUsername(), saveCommentThing.getId()))
                {
                    menu.add(0, Constants.DIALOG_UNSAVE_COMMENT, Menu.NONE, "Unsave");
                }
                else
                {
                    menu.add(0, Constants.DIALOG_SAVE_COMMENT, Menu.NONE, "Save");
                }
            }

            // Make sure the user isn't '[deleted]'
            if (!item.isDeletedUser()) {
                menu.add(0, Constants.DIALOG_VIEW_PROFILE, Menu.NONE,
                         String.format(getResources().getString(R.string.user_profile), item.getAuthor()));
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        int rowId = (int) info.id;

        switch (item.getItemId()) {
        case Constants.SAVE_CONTEXT_ITEM:
            new SaveTask(true, getOpThingInfo(), mSettings, this).execute();
            return true;

        case Constants.UNSAVE_CONTEXT_ITEM:
            new SaveTask(false, getOpThingInfo(), mSettings, this).execute();
            return true;

        case Constants.HIDE_CONTEXT_ITEM:
            new HideTask(true, getOpThingInfo(), mSettings, this).execute();
            return true;

        case Constants.UNHIDE_CONTEXT_ITEM:
            new HideTask(false, getOpThingInfo(), mSettings, this).execute();
            return true;

        case Constants.SHARE_CONTEXT_ITEM:
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("text/plain");

            intent.putExtra(Intent.EXTRA_TEXT, getOpThingInfo().getUrl());

            try {
                startActivity(Intent.createChooser(intent, "Share Link"));
            } catch (android.content.ActivityNotFoundException ex) {

            }

            return true;

        case Constants.DIALOG_HIDE_COMMENT:
            hideComment(rowId);
            return true;

        case Constants.DIALOG_SHOW_COMMENT:
            showComment(rowId);
            return true;

        case Constants.DIALOG_SAVE_COMMENT:
            String user = mSettings.getUsername();
            String author = saveCommentThing.getAuthor();
            String body = saveCommentThing.getBody_html();
            String linkId = saveCommentThing.getLink_id();
            String commentId = saveCommentThing.getId();

            sdb.addSavedContent(new SavedContent(user, author, body, linkId, commentId, mSubreddit));
            Toast.makeText(CommentsListActivity.this, "Comment saved", Toast.LENGTH_LONG).show();
            return true;

        case Constants.DIALOG_UNSAVE_COMMENT:
            user = mSettings.getUsername();
            author = saveCommentThing.getAuthor();
            body = saveCommentThing.getBody_html();
            linkId = saveCommentThing.getLink_id();
            commentId = saveCommentThing.getId();

            sdb.deleteSavedContent(new SavedContent(user, author, body, linkId, commentId, mSubreddit));
            Toast.makeText(CommentsListActivity.this, "Comment unsaved", Toast.LENGTH_LONG).show();
            return true;

        case Constants.DIALOG_FOCUS_PARENT:
            if (mCommentsAdapter.getItem(rowId).isParentAComment()) {
                int myIndent = mCommentsAdapter.getItem(rowId).getIndent();
                int parentRowId;
                for (parentRowId = rowId - 1; parentRowId >= 0; parentRowId--)
                    if (mCommentsAdapter.getItem(parentRowId).getIndent() < myIndent)
                        break;
                getListView().setSelection(parentRowId);
            } else {
                // Focus the OP
                getListView().setSelection(0);
            }
            return true;

        case Constants.DIALOG_VIEW_PROFILE:
            Intent i = new Intent(this, ProfileActivity.class);
            i.setData(Util.createProfileUri(mCommentsAdapter.getItem(rowId).getAuthor()));
            startActivity(i);
            return true;

        case Constants.DIALOG_EDIT:
            mReplyTargetName = mCommentsAdapter.getItem(rowId).getName();
            mEditTargetBody = mCommentsAdapter.getItem(rowId).getBody();
            showDialog(Constants.DIALOG_EDIT);
            return true;

        case Constants.DIALOG_DELETE:
            mReplyTargetName = mCommentsAdapter.getItem(rowId).getName();
            // It must be a comment, since the OP selftext is reached via options menu, not context menu
            mDeleteTargetKind = Constants.COMMENT_KIND;
            showDialog(Constants.DIALOG_DELETE);
            return true;

        case Constants.DIALOG_REPORT:
            mReportTargetName = mCommentsAdapter.getItem(rowId).getName();
            showDialog(Constants.DIALOG_REPORT);
            return true;
        case Constants.COPY_CONTEXT_ITEM:
            String url = getOpThingInfo().getUrl();
            int sdk = android.os.Build.VERSION.SDK_INT;
            if(sdk < android.os.Build.VERSION_CODES.HONEYCOMB) {
                android.text.ClipboardManager clipboard = (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setText(url);
            } else {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText(url,url);
                clipboard.setPrimaryClip(clip);
            }
            return true;
        case Constants.DIALOG_FULL_CONTEXT:
            setContextOPID(mCommentsAdapter.getItem(rowId).getId());
            // 10k denotes 'full context'
            setContextCount(10000);
            getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            return true;
        case Constants.DIALOG_VIEW_CONTEXT:
            setContextOPID(mCommentsAdapter.getItem(rowId).getId());
            setContextCount(3);
            getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            return true;
        case Constants.DIALOG_THREAD_CLICK:
            // Reset context information so whole thread is shown.
            resetContextInfo();
            getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
            return true;

        default:
            return super.onContextItemSelected(item);
        }
    }

    private void hideComment(int rowId) {
        ThingInfo headComment = mCommentsAdapter.getItem(rowId);
        int myIndent = headComment.getIndent();
        headComment.setHiddenCommentHead(true);

        // Hide everything after the row.
        for (int i = rowId + 1; i < mCommentsAdapter.getCount(); i++) {
            ThingInfo ci = mCommentsAdapter.getItem(i);
            if (ci.getIndent() <= myIndent)
                break;
            ci.setHiddenCommentDescendant(true);
        }
        mCommentsAdapter.notifyDataSetChanged();
    }

    private void showComment(int rowId) {
        ThingInfo headComment = mCommentsAdapter.getItem(rowId);
        headComment.setHiddenCommentHead(false);
        int stopIndent = headComment.getIndent();
        int skipIndentAbove = -1;
        for (int i = rowId + 1; i < mCommentsAdapter.getCount(); i++) {
            ThingInfo ci = mCommentsAdapter.getItem(i);
            int ciIndent = ci.getIndent();
            if (ciIndent <= stopIndent)
                break;
            if (skipIndentAbove != -1 && ciIndent > skipIndentAbove)
                continue;

            ci.setHiddenCommentDescendant(false);

            // skip nested hidden comments (e.g. you collapsed child first, then root. now expanding root, but don't expand child)
            if (ci.isHiddenCommentHead())
                skipIndentAbove = ci.getIndent();
            else
                skipIndentAbove = -1;
        }
        mCommentsAdapter.notifyDataSetChanged();
    }

    private void findCommentText(String search_text, boolean wrap, boolean next) {
        last_search_string = search_text;
        int current_position = next
                               ? (last_found_position + 1) % mCommentsAdapter.getCount()
                               : Math.max(0, getSelectedItemPosition());

        if ( getFoundPosition(current_position, mCommentsAdapter.getCount(), search_text) ) {
            mCommentsAdapter.notifyDataSetChanged();
            return;
        }

        if ( wrap ) {
            if (Constants.LOGGING) Log.d(TAG, "Continuing search from top...");
            if ( getFoundPosition(0, current_position, search_text) ) {
                mCommentsAdapter.notifyDataSetChanged();
                return;
            }
        }

        mCommentsAdapter.notifyDataSetChanged();

        String not_found_msg = getResources().getString(R.string.find_not_found, search_text);
        Toast.makeText(CommentsListActivity.this, not_found_msg, Toast.LENGTH_LONG).show();
    }

    private boolean getFoundPosition(int start_index, int end_index, String search_text) {
        for (int i = start_index; i < end_index; i++) {
            ThingInfo ci = mCommentsAdapter.getItem(i);

            if (ci == null) continue;

            String comment_body = ci.getBody();
            if (comment_body == null) continue;

            if (comment_body.toLowerCase().contains(search_text)) {
                final int position = i;
                getListView().post(new Runnable() {
                    @Override
                    public void run() {
                        setSelection(position);
                        getListView().requestFocus();
                    }
                });

                last_found_position = i;
                return true;
            }
        }
        last_found_position = -1;
        return false;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        final Dialog dialog;
        ProgressDialog pdialog;
        AlertDialog.Builder builder;
        LayoutInflater inflater;

        switch (id) {
        case Constants.DIALOG_LOGIN:
            dialog = new LoginDialog(this, mSettings, false) {
                @Override
                public void onLoginChosen(String user, String password) {
                    removeDialog(Constants.DIALOG_LOGIN);
                    new MyLoginTask(user, password).execute();
                }
            };
            break;

        case Constants.DIALOG_COMMENT_CLICK:
            dialog = new CommentClickDialog(this, mSettings);
            break;

        case Constants.DIALOG_REPLY:
        {
            dialog = new Dialog(this, mSettings.getDialogTheme());
            dialog.setContentView(R.layout.compose_reply_dialog);
            final EditText replyBody = (EditText) dialog.findViewById(R.id.body);
            final Button replySaveButton = (Button) dialog.findViewById(R.id.reply_save_button);
            final Button replyCancelButton = (Button) dialog.findViewById(R.id.reply_cancel_button);

            replySaveButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mReplyTargetName != null) {
                        new CommentReplyTask(mReplyTargetName).execute(replyBody.getText().toString());
                        dialog.dismiss();
                    }
                    else {
                        Common.showErrorToast("Error replying. Please try again.", Toast.LENGTH_SHORT, CommentsListActivity.this);
                    }
                }
            });
            replyCancelButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mVoteTargetThing.setReplyDraft(replyBody.getText().toString());
                    dialog.cancel();
                }
            });
            dialog.setCancelable(false);  // disallow the BACK key
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    replyBody.setText("");
                }
            });
            break;
        }

        case Constants.DIALOG_EDIT:
        {
            dialog = new Dialog(this, mSettings.getDialogTheme());
            dialog.setContentView(R.layout.compose_reply_dialog);
            final EditText replyBody = (EditText) dialog.findViewById(R.id.body);
            final Button replySaveButton = (Button) dialog.findViewById(R.id.reply_save_button);
            final Button replyCancelButton = (Button) dialog.findViewById(R.id.reply_cancel_button);

            replyBody.setText(mEditTargetBody);

            replySaveButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (mReplyTargetName != null) {
                        new EditTask(mReplyTargetName).execute(replyBody.getText().toString());
                        dialog.dismiss();
                    }
                    else {
                        Common.showErrorToast("Error editing. Please try again.", Toast.LENGTH_SHORT, CommentsListActivity.this);
                    }
                }
            });
            replyCancelButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    dialog.cancel();
                }
            });
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    replyBody.setText("");
                }
            });
            break;
        }

        case Constants.DIALOG_DELETE:
            builder = new AlertDialog.Builder(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            builder.setTitle("Really delete this?");
            builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int item) {
                    removeDialog(Constants.DIALOG_DELETE);
                    new DeleteTask(mDeleteTargetKind).execute(mReplyTargetName);
                }
            })
            .setNegativeButton("No", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            dialog = builder.create();
            break;

        case Constants.DIALOG_SORT_BY:
            builder = new AlertDialog.Builder(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            builder.setTitle("Sort by:");
            int selectedSortBy = -1;
            for (int i = 0; i < Constants.CommentsSort.SORT_BY_URL_CHOICES.length; i++) {
                if (Constants.CommentsSort.SORT_BY_URL_CHOICES[i].equals(mSettings.getCommentsSortByUrl())) {
                    selectedSortBy = i;
                    break;
                }
            }
            builder.setSingleChoiceItems(Constants.CommentsSort.SORT_BY_CHOICES, selectedSortBy, sortByOnClickListener);
            dialog = builder.create();
            break;

        case Constants.DIALOG_REPORT:
            builder = new AlertDialog.Builder(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            builder.setTitle("Really report this?");
            builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int item) {
                    removeDialog(Constants.DIALOG_REPORT);
                    new ReportTask(mReportTargetName.toString()).execute();
                }
            })
            .setNegativeButton("No", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            dialog = builder.create();
            break;

        // "Please wait"
        case Constants.DIALOG_DELETING:
            pdialog = new ProgressDialog(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            pdialog.setMessage("Deleting...");
            pdialog.setIndeterminate(true);
            pdialog.setCancelable(true);
            dialog = pdialog;
            break;
        case Constants.DIALOG_EDITING:
            pdialog = new ProgressDialog(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            pdialog.setMessage("Submitting edit...");
            pdialog.setIndeterminate(true);
            pdialog.setCancelable(true);
            dialog = pdialog;
            break;
        case Constants.DIALOG_LOGGING_IN:
            pdialog = new ProgressDialog(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            pdialog.setMessage("Logging in...");
            pdialog.setIndeterminate(true);
            pdialog.setCancelable(true);
            dialog = pdialog;
            break;
        case Constants.DIALOG_REPLYING:
            pdialog = new ProgressDialog(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            pdialog.setMessage("Sending reply...");
            pdialog.setIndeterminate(true);
            pdialog.setCancelable(true);
            dialog = pdialog;
            break;
        case Constants.DIALOG_FIND:
            inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            View content = inflater.inflate(R.layout.dialog_find, null);
            final EditText find_box = (EditText) content.findViewById(R.id.input_find_box);
//    		final CheckBox wrap_box = (CheckBox) content.findViewById(R.id.find_wrap_checkbox);

            builder = new AlertDialog.Builder(new ContextThemeWrapper(this, mSettings.getDialogTheme()));
            builder.setView(content);
            builder.setTitle(R.string.find)
            .setPositiveButton(R.string.find, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String search_text = find_box.getText().toString().toLowerCase();
//					findCommentText(search_text, wrap_box.isChecked(), false);
                    findCommentText(search_text, true, false);
                }
            })
            .setNegativeButton("Cancel", null);
            dialog = builder.create();
            break;
        default:
            throw new IllegalArgumentException("Unexpected dialog id "+id);
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        StringBuilder sb;

        switch (id) {
        case Constants.DIALOG_LOGIN:
            if (mSettings.getUsername() != null) {
                final TextView loginUsernameInput = (TextView) dialog.findViewById(R.id.login_username_input);
                loginUsernameInput.setText(mSettings.getUsername());
            }
            final TextView loginPasswordInput = (TextView) dialog.findViewById(R.id.login_password_input);
            loginPasswordInput.setText("");
            break;

        case Constants.DIALOG_COMMENT_CLICK:
            if (mVoteTargetThing == null)
                break;
            Boolean likes;
            final TextView titleView = (TextView) dialog.findViewById(R.id.title);
            final TextView urlView = (TextView) dialog.findViewById(R.id.url);
            final TextView submissionStuffView = (TextView) dialog.findViewById(R.id.submissionTime_submitter_subreddit);
            final Button linkButton = (Button) dialog.findViewById(R.id.thread_link_button);
            linkButton.setEnabled(false);

            if (mVoteTargetThing == getOpThingInfo()) {
                likes = mVoteTargetThing.getLikes();
                titleView.setVisibility(View.VISIBLE);
                titleView.setText(getOpThingInfo().getTitle());
                urlView.setVisibility(View.VISIBLE);
                urlView.setText(getOpThingInfo().getUrl());
                submissionStuffView.setVisibility(View.VISIBLE);
                sb = new StringBuilder(Util.getTimeAgo(getOpThingInfo().getCreated_utc(), getResources()))
                .append(" by ").append(getOpThingInfo().getAuthor());
                submissionStuffView.setText(sb);
                // For self posts, you're already there!
                if (getOpThingInfo().getDomain().toLowerCase().startsWith("self.")) {
                    linkButton.setText(R.string.comment_links_button);
                    // Lazy-load the URL list to make the list-loading more 'snappy'.
                    if (mVoteTargetThing.getUrls() != null && mVoteTargetThing.getUrls().isEmpty()) {
                        Markdown.getURLs(mVoteTargetThing.getSelftext(), mVoteTargetThing.getUrls());
                    }
                    if (mVoteTargetThing.getUrls() != null && !mVoteTargetThing.getUrls().isEmpty()) {
                        linkButton.setEnabled(true);
                        linkButton.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                removeDialog(Constants.DIALOG_COMMENT_CLICK);
                                Common.showLinksDialog(CommentsListActivity.this, mSettings, mVoteTargetThing);
                            }
                        });
                    }
                } else {
                    final String url = getOpThingInfo().getUrl();
                    linkButton.setText(R.string.thread_link_button);
                    linkButton.setOnClickListener(new OnClickListener() {
                        public void onClick(View v) {
                            removeDialog(Constants.DIALOG_COMMENT_CLICK);
                            setLinkClicked(getOpThingInfo());
                            Common.launchBrowser(CommentsListActivity.this, url,
                                                 Util.createThreadUri(getOpThingInfo()).toString(),
                                                 false, false, mSettings.isUseExternalBrowser(),
                                                 mSettings.isSaveHistory());
                        }
                    });
                    linkButton.setEnabled(true);
                }
            } else {
                titleView.setText("Comment by " + mVoteTargetThing.getAuthor());
                likes = mVoteTargetThing.getLikes();
                urlView.setVisibility(View.INVISIBLE);
                submissionStuffView.setVisibility(View.INVISIBLE);

                // Get embedded URLs
                linkButton.setText(R.string.comment_links_button);
                if (mVoteTargetThing.getUrls() != null && !mVoteTargetThing.getUrls().isEmpty()) {
                    linkButton.setEnabled(true);
                    linkButton.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            removeDialog(Constants.DIALOG_COMMENT_CLICK);
                            Common.showLinksDialog(CommentsListActivity.this, mSettings, mVoteTargetThing);
                        }
                    });
                }
            }
            final CheckBox voteUpButton = (CheckBox) dialog.findViewById(R.id.vote_up_button);
            final CheckBox voteDownButton = (CheckBox) dialog.findViewById(R.id.vote_down_button);
            final Button replyButton = (Button) dialog.findViewById(R.id.reply_button);
            final Button loginButton = (Button) dialog.findViewById(R.id.login_button);

            // Only show upvote/downvote if user is logged in
            if (mSettings.isLoggedIn()) {
                loginButton.setVisibility(View.GONE);
                voteUpButton.setVisibility(View.VISIBLE);
                voteDownButton.setVisibility(View.VISIBLE);
                replyButton.setEnabled(true);

                // Make sure the setChecked() actions don't actually vote just yet.
                voteUpButton.setOnCheckedChangeListener(null);
                voteDownButton.setOnCheckedChangeListener(null);

                // Set initial states of the vote buttons based on user's past actions
                if (likes == null) {
                    // User is currently neutral
                    voteUpButton.setChecked(false);
                    voteDownButton.setChecked(false);
                } else if (likes == true) {
                    // User currenty likes it
                    voteUpButton.setChecked(true);
                    voteDownButton.setChecked(false);
                } else {
                    // User currently dislikes it
                    voteUpButton.setChecked(false);
                    voteDownButton.setChecked(true);
                }
                // Now we want the user to be able to vote.
                voteUpButton.setOnCheckedChangeListener(voteUpOnCheckedChangeListener);
                voteDownButton.setOnCheckedChangeListener(voteDownOnCheckedChangeListener);

                // The "reply" button
                replyButton.setOnClickListener(replyOnClickListener);
            } else {
                replyButton.setEnabled(false);

                voteUpButton.setVisibility(View.GONE);
                voteDownButton.setVisibility(View.GONE);
                loginButton.setVisibility(View.VISIBLE);
                loginButton.setOnClickListener(loginOnClickListener);
            }
            break;

        case Constants.DIALOG_REPLY:
            if (mVoteTargetThing != null) {
                if (mVoteTargetThing.getReplyDraft() != null && !mShouldClearReply) {
                    EditText replyBodyView = (EditText) dialog.findViewById(R.id.body);
                    replyBodyView.setText(mVoteTargetThing.getReplyDraft());
                }
                else {
                    EditText replyBodyView = (EditText) dialog.findViewById(R.id.body);
                    replyBodyView.setText("");
                    mShouldClearReply = false;
                }
            }
            break;

        case Constants.DIALOG_EDIT:
            EditText replyBodyView = (EditText) dialog.findViewById(R.id.body);
            replyBodyView.setText(mEditTargetBody);
            break;

        default:
            // No preparation based on app state is required.
            break;
        }
    }

    /**
     * Class to cache the view content information, so it doesn't have to be loaded while the user is
     * scrolling up and down in the threads list view.
     * @see <a href="http://developer.android.com/training/improving-layouts/smooth-scrolling.html">this</a>
     */
    private static class ViewHolder {
        TextView votesView;
        TextView textFlairView;
        TextView submitterView;
        TextView bodyView;

        TextView submissionTimeView;
        ImageView voteUpView;
        ImageView voteDownView;

        View indentViews[];
    }

    /**
     * Pre-load the view items in a comment's View to implement the ViewHolder pattern.
     * The views are reused so much that having to
     * @param theCommentView the Comment View item to load elements for. The ViewHolder will be stored in the view's tag.
     */
    private static void loadAndStoreViewHolder(View theCommentView) {
        ViewHolder vh = new ViewHolder();
        theCommentView.setTag(vh);
        vh.votesView = (TextView) theCommentView.findViewById(R.id.votes);
        vh.textFlairView = (TextView) theCommentView.findViewById(R.id.textFlair);
        vh.submitterView = (TextView) theCommentView.findViewById(R.id.submitter);
        vh.bodyView = (TextView) theCommentView.findViewById(R.id.body);

        vh.submissionTimeView = (TextView) theCommentView.findViewById(R.id.submissionTime);
        vh.voteUpView = (ImageView) theCommentView.findViewById(R.id.vote_up_image);
        vh.voteDownView = (ImageView) theCommentView.findViewById(R.id.vote_down_image);
        vh.indentViews = new View[] {
                theCommentView.findViewById(R.id.left_indent1),
                theCommentView.findViewById(R.id.left_indent2),
                theCommentView.findViewById(R.id.left_indent3),
                theCommentView.findViewById(R.id.left_indent4),
                theCommentView.findViewById(R.id.left_indent5),
                theCommentView.findViewById(R.id.left_indent6),
                theCommentView.findViewById(R.id.left_indent7),
                theCommentView.findViewById(R.id.left_indent8)
        };
    }
    public static void fillCommentsListItemView(View view, ThingInfo item, RedditSettings settings) {
        if (view.getTag() == null) {
            loadAndStoreViewHolder(view);
        }

        ViewHolder vh = (ViewHolder)view.getTag();

        try {
            vh.votesView.setText(Util.showNumPoints(item.getUps() - item.getDowns()));
        } catch (NumberFormatException e) {
            // This happens because "ups" comes after the potentially long "replies" object,
            // so the ListView might try to display the View before "ups" in JSON has been parsed.
            if (Constants.LOGGING) Log.e(TAG, "getView, normal comment", e);
        }
        if (item.getSSAuthor() != null) {
            vh.submitterView.setText(item.getSSAuthor());
        } else {
            vh.submitterView.setText(item.getAuthor());
        }
        vh.submissionTimeView.setText(Util.getTimeAgo(item.getCreated_utc(), view.getContext().getResources()));

        if (item.getSpannedBody() != null) {
            vh.bodyView.setText(item.getSpannedBody());
        } else {
            vh.bodyView.setText(item.getBody());
        }

        setCommentIndent(view, item.getIndent(), settings);

        boolean hasFlair = !StringUtils.isEmpty(item.getAuthor_flair_text());
        vh.textFlairView.setVisibility(hasFlair ? View.VISIBLE : View.GONE);
        vh.textFlairView.setText(item.getAuthor_flair_text());

        if (vh.voteUpView != null && vh.voteDownView != null) {
            if (item.getLikes() == null || item.isDeletedUser()) {
                vh.voteUpView.setVisibility(View.GONE);
                vh.voteDownView.setVisibility(View.GONE);
            }
            else if (Boolean.TRUE.equals(item.getLikes())) {
                vh.voteUpView.setVisibility(View.VISIBLE);
                vh.voteDownView.setVisibility(View.GONE);
            }
            else if (Boolean.FALSE.equals(item.getLikes())) {
                vh.voteUpView.setVisibility(View.GONE);
                vh.voteDownView.setVisibility(View.VISIBLE);
            }
        }
    }


    private final CompoundButton.OnCheckedChangeListener voteUpOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            removeDialog(Constants.DIALOG_COMMENT_CLICK);
            String thingFullname = mVoteTargetThing.getName();
            if (isChecked)
                new VoteTask(thingFullname, 1).execute();
            else
                new VoteTask(thingFullname, 0).execute();
        }
    };
    private final CompoundButton.OnCheckedChangeListener voteDownOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            removeDialog(Constants.DIALOG_COMMENT_CLICK);
            String thingFullname = mVoteTargetThing.getName();

            if (isChecked)
                new VoteTask(thingFullname, -1).execute();
            else
                new VoteTask(thingFullname, 0).execute();
        }
    };

    private final OnClickListener replyOnClickListener = new OnClickListener() {
        public void onClick(View v) {
            removeDialog(Constants.DIALOG_COMMENT_CLICK);
            showDialog(Constants.DIALOG_REPLY);
        }
    };

    private final OnClickListener loginOnClickListener = new OnClickListener() {
        public void onClick(View v) {
            removeDialog(Constants.DIALOG_COMMENT_CLICK);
            showDialog(Constants.DIALOG_LOGIN);
        }
    };

    private final DialogInterface.OnClickListener sortByOnClickListener = new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int item) {
            dialog.dismiss();
            mSettings.setCommentsSortByUrl(Constants.CommentsSort.SORT_BY_URL_CHOICES[item]);
            getNewDownloadCommentsTask().execute(Constants.DEFAULT_COMMENT_DOWNLOAD_LIMIT);
        }
    };

    private final ThumbnailOnClickListenerFactory mThumbnailOnClickListenerFactory
    = new ThumbnailOnClickListenerFactory() {
        @Override
        public OnClickListener getThumbnailOnClickListener(final ThingInfo threadThingInfo, final Activity activity) {
            return new OnClickListener() {
                public void onClick(View v) {
                    setLinkClicked(threadThingInfo);
                    Common.launchBrowser(
                        activity,
                        threadThingInfo.getUrl(),
                        Util.createThreadUri(threadThingInfo).toString(),
                        false,
                        false,
                        mSettings.isUseExternalBrowser(),
                        mSettings.isSaveHistory()
                    );
                }
            };
        }
    };

    private void setLinkClicked(ThingInfo threadThingInfo) {
        threadThingInfo.setClicked(true);
        mCommentsAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (Constants.LOGGING) Log.d(TAG, "onSaveInstanceState called");
        state.putString(Constants.REPLY_TARGET_NAME_KEY, mReplyTargetName);
        state.putString(Constants.REPORT_TARGET_NAME_KEY, mReportTargetName);
        state.putString(Constants.EDIT_TARGET_BODY_KEY, mEditTargetBody);
        state.putString(Constants.DELETE_TARGET_KIND_KEY, mDeleteTargetKind);
        state.putString(Constants.SUBREDDIT_KEY, mSubreddit);
        state.putString(Constants.THREAD_ID_KEY, mThreadId);
        state.putString(Constants.THREAD_TITLE_KEY, mThreadTitle);
        state.putParcelable(Constants.VOTE_TARGET_THING_INFO_KEY, mVoteTargetThing);
        state.putString(Constants.CONTEXT_OP_ID_KEY, mContextOPID);
        state.putInt(Constants.CONTEXT_COUNT_KEY, mContextCount);
        super.onSaveInstanceState(state);
    }

    /**
     * Called to "thaw" re-animate the app from a previous onSaveInstanceState().
     *
     * @see android.app.Activity#onRestoreInstanceState
     */
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        final int[] myDialogs = {
            Constants.DIALOG_COMMENT_CLICK,
            Constants.DIALOG_DELETE,
            Constants.DIALOG_DELETING,
            Constants.DIALOG_EDIT,
            Constants.DIALOG_EDITING,
            Constants.DIALOG_LOGGING_IN,
            Constants.DIALOG_LOGIN,
            Constants.DIALOG_REPLY,
            Constants.DIALOG_REPLYING,
            Constants.DIALOG_SORT_BY,
            Constants.DIALOG_REPORT
        };
        for (int dialog : myDialogs) {
            try {
                removeDialog(dialog);
            } catch (IllegalArgumentException e) {
                // Ignore.
            }
        }
    }
}
